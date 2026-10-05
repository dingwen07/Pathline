package net.extrawdw.apps.locationhistory.data.repo

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.extrawdw.apps.locationhistory.core.*
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.*
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import net.extrawdw.apps.locationhistory.domain.MemoryEntry
import net.extrawdw.apps.locationhistory.domain.MemoryMap
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DataManagerTest {
    private lateinit var db: AppDatabase
    private lateinit var manager: DataManager
    private lateinit var annotations: AnnotationStore
    private val zone = ZoneId.of("Asia/Singapore")
    private val day = LocalDate.of(2025, 6, 10).toEpochDay()
    private fun at(offset: Long, hour: Long = 0) = TimeBuckets.dayRangeMillis(day + offset, zone).first + hour * 3_600_000

    private fun open(failPlaceDelete: Boolean = false) {
        System.loadLibrary("sqlcipher")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .setDriver(SQLCipherDriver("data-manager-test".toByteArray(), null, null))
            .addCallback(object : RoomDatabase.Callback() {
                override suspend fun onCreate(connection: SQLiteConnection) {
                    AppDatabase.DIRTY_TRIGGERS.forEach(connection::execSQL)
                    if (failPlaceDelete) connection.execSQL("CREATE TRIGGER reject_place_delete BEFORE DELETE ON places BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
                }
            }).build()
        annotations = AnnotationStore(db.tagDao(), db.annotationDao(), db.conceptDao())
        manager = DataManager(db, annotations, TimelineWriteLock(), DataOperationLock())
    }

    @After fun close() { if (::db.isInitialized) db.close() }

    @Test fun capsSpanningVisitAtBothMidnightsAndKeepsAnnotationsAndOnlyOutsideSamples() = runBlocking {
        open()
        val place = place()
        val id = visit(place, at(-1, 22), at(1, 2), confirmed = false)
        annotations.saveEdits(AnnotationTarget.VISIT, id, "retained note", listOf("kept"))
        samples(at(-1, 22), at(-1, 23), at(0), at(0, 12), at(1), at(1, 2))
        manager.deleteDateRange(day, day, EdgeActivityBehavior.CAP, true, zone)
        val kept = db.visitDao().listForPlace(place).sortedBy { it.startMs }
        assertEquals(2, kept.size)
        assertEquals(listOf(at(-1, 22) to at(0), at(1) to at(1, 2)), kept.map { it.startMs to it.endMs })
        assertEquals(id, kept.first().id)
        assertTrue(kept.all { it.stopMerge && !it.confirmed })
        kept.forEach { assertEquals("retained note", annotations.getNote(AnnotationTarget.VISIT, it.id)) }
        assertEquals(listOf(at(-1, 22), at(-1, 23), at(1), at(1, 2)), allSamples().map { it.timestampMs })
        db.visitDao().deleteUnconfirmedOverlapping(at(-2), at(3))
        assertEquals(2, db.visitDao().count())
        assertNotNull(db.placeDao().byId(place))
    }

    @Test fun capsTripGeometryAndClearsEndpointsOnTheDeletedSides() = runBlocking {
        open()
        val from = visit(place(), at(-1, 20), at(-1, 21))
        val to = visit(place("End"), at(1, 4), at(1, 5))
        trip(at(-1, 22), at(1, 2), from, to)
        samples(at(-1, 22), at(-1, 23), at(0, 4), at(1), at(1, 2))
        manager.deleteDateRange(day, day, EdgeActivityBehavior.CAP, false, zone)
        val trips = db.tripDao().overlapping(at(-2), at(3))
        assertEquals(2, trips.size)
        assertEquals(at(0), trips.first().endMs)
        assertEquals(at(1), trips.last().startMs)
        assertEquals(from, trips.first().fromVisitId)
        assertNull(trips.first().toVisitId)
        assertNull(trips.last().fromVisitId)
        assertEquals(to, trips.last().toVisitId)
        assertTrue(trips.all { it.stopMerge && Geo.decodePolyline(it.encodedPolyline).size == 2 })
        db.tripDao().deleteUnconfirmedOverlapping(at(-2), at(3))
        assertEquals(2, db.tripDao().count())
    }

    @Test fun wholeActivitiesAlsoDeleteTheirSamplesOutsideTheSelectedDays() = runBlocking {
        open()
        visit(place(), at(-1, 22), at(0, 2))
        trip(at(0, 23), at(1, 3))
        samples(at(-1, 21), at(-1, 22), at(0, 2), at(0, 12), at(1, 3), at(1, 4))
        manager.deleteDateRange(day, day, EdgeActivityBehavior.DELETE_WHOLE, false, zone)
        assertEquals(0, db.visitDao().count())
        assertEquals(0, db.tripDao().count())
        assertEquals(listOf(at(-1, 21), at(1, 4)), allSamples().map { it.timestampMs })
    }

    @Test fun deleteEmptyPlacesOnlyRemovesPlacesMadeEmptyByThisPurge() = runBlocking {
        open()
        val removed = place()
        val alreadyEmpty = place("Empty before")
        val retained = place("Still visited")
        visit(removed, at(0, 2), at(0, 3))
        visit(retained, at(0, 3), at(0, 4))
        visit(retained, at(1, 3), at(1, 4))
        annotations.saveEdits(AnnotationTarget.PLACE, removed, "removed", listOf("tag"))
        manager.deleteDateRange(day, day, EdgeActivityBehavior.CAP, true, zone)
        assertNull(db.placeDao().byId(removed))
        assertNull(annotations.getNote(AnnotationTarget.PLACE, removed))
        assertNotNull(db.placeDao().byId(alreadyEmpty))
        assertNotNull(db.placeDao().byId(retained))
    }

    @Test fun deletingPlaceDefaultsToRemovingVisitsConnectedTripsAndAllTheirSamples() = runBlocking {
        open()
        val place = place()
        val visit = visit(place, at(0, 4), at(0, 6))
        val other = visit(place("Other"), at(0, 9), at(0, 10))
        trip(at(0, 2), at(0, 4), to = visit)
        trip(at(0, 6), at(0, 9), from = visit, to = other)
        samples(at(0, 1), at(0, 2), at(0, 4), at(0, 6), at(0, 9), at(0, 10))
        annotations.setNote(AnnotationTarget.VISIT, visit, "private visit")
        assertTrue(manager.deletePlace(place, DeletePlaceOptions(), zone))
        assertNull(db.placeDao().byId(place))
        assertNull(db.visitDao().byId(visit))
        assertEquals(0, db.tripDao().count())
        assertNotNull(db.visitDao().byId(other))
        assertNull(annotations.getNote(AnnotationTarget.VISIT, visit))
        assertEquals(listOf(at(0, 1), at(0, 10)), allSamples().map { it.timestampMs })
    }

    @Test fun keepingTripsDetachesAndProtectsThemWhenPlaceVisitsDisappear() = runBlocking {
        open()
        val place = place()
        val visit = visit(place, at(0, 4), at(0, 6))
        val inbound = trip(at(0, 2), at(0, 4), to = visit)
        val outbound = trip(at(0, 6), at(0, 8), from = visit)
        samples(at(0, 2), at(0, 4), at(0, 6), at(0, 8))
        manager.deletePlace(place, DeletePlaceOptions(deleteConnectedTrips = false), zone)
        assertEquals(2, db.tripDao().count())
        assertNull(db.tripDao().byId(inbound)!!.toVisitId)
        assertNull(db.tripDao().byId(outbound)!!.fromVisitId)
        assertTrue(db.tripDao().byId(inbound)!!.stopMerge)
        db.tripDao().deleteUnconfirmedOverlapping(at(0), at(1))
        assertEquals(2, db.tripDao().count())
        assertEquals(listOf(at(0, 2), at(0, 8)), allSamples().map { it.timestampMs })
    }

    @Test fun deletingVisitDaysIncludesEveryDayOfAnOvernightVisitAndCapsOtherActivity() = runBlocking {
        open()
        val place = place()
        visit(place, at(0, 23), at(1, 2))
        val other = visit(place("Other"), at(-1, 20), at(0, 2))
        trip(at(1, 20), at(2, 3))
        samples(at(-1, 23), at(0, 3), at(1, 20), at(2), at(2, 3))
        manager.deletePlace(place, DeletePlaceOptions(false, true), zone)
        assertEquals(at(0), db.visitDao().byId(other)!!.endMs)
        assertEquals(at(2), db.tripDao().overlapping(at(1), at(3)).single().startMs)
        assertEquals(listOf(at(-1, 23), at(2), at(2, 3)), allSamples().map { it.timestampMs })
    }

    @Test fun resetClearsDeletionHistoryDirtyMarkersAndIdentityCountersForFreshRestore() = runBlocking {
        open()
        visit(place(), at(0, 4), at(0, 6))
        trip(at(0, 1), at(0, 2))
        samples(at(0, 1), at(0, 4))
        annotations.saveEdits(AnnotationTarget.PLACE, 1, "note", listOf("tag"))
        db.dataManagementDao().insertDeletedRanges(listOf(DeletedTimeRangeEntity(at(-2), at(-1))))
        manager.resetAllData()
        assertEquals(0, db.visitDao().count())
        assertEquals(0, db.tripDao().count())
        assertEquals(0, db.placeDao().count())
        assertEquals(0L, db.locationSampleDao().count())
        assertTrue(db.backupDao().allTags().isEmpty())
        assertTrue(db.backupDao().allAnnotations().isEmpty())
        assertTrue(db.backupDao().allDirty().isEmpty())
        assertTrue(db.dataManagementDao().deletedRanges().isEmpty())
        assertEquals(1L, place("Fresh database"))
        assertEquals(listOf(1L), db.locationSampleDao().insertRecorded(listOf(sample(at(0, 1)))))
    }

    @Test fun failedPlaceDeleteRollsBackRawDataActivitiesAnnotationsAndDeletionRanges() = runBlocking {
        open(failPlaceDelete = true)
        val place = place()
        visit(place, at(0, 4), at(0, 6))
        samples(at(0, 4), at(0, 5))
        annotations.setNote(AnnotationTarget.PLACE, place, "keep me")
        try { manager.deletePlace(place, DeletePlaceOptions(), zone); fail("expected rollback") }
        catch (_: android.database.SQLException) { }
        catch (error: Exception) { assertTrue(error.message.orEmpty().contains("rollback")) }
        assertEquals(2L, db.locationSampleDao().count())
        assertEquals(1, db.visitDao().count())
        assertEquals("keep me", annotations.getNote(AnnotationTarget.PLACE, place))
        assertTrue(db.dataManagementDao().deletedRanges().isEmpty())
    }

    @Test fun zeroDurationVisitAndItsExactTimestampAreDeleted() = runBlocking {
        open()
        val place = place()
        visit(place, at(0, 4), at(0, 4))
        samples(at(0, 4), at(0, 4) + 1)
        manager.deletePlace(place, DeletePlaceOptions(false), zone)
        assertEquals(listOf(at(0, 4) + 1), allSamples().map { it.timestampMs })
        assertEquals(0, db.visitDao().count())
    }

    @Test fun localDatePurgeUsesDstMidnightsInsteadOfAdding24Hours() = runBlocking {
        open()
        val dstZone = ZoneId.of("America/New_York")
        val dstDay = LocalDate.of(2025, 3, 9).toEpochDay()
        val range = TimeBuckets.dayRangeMillis(dstDay, dstZone)
        assertEquals(23 * 3_600_000L, range.last + 1 - range.first)
        samples(range.first - 1, range.first, range.last, range.last + 1)
        manager.deleteDateRange(dstDay, dstDay, EdgeActivityBehavior.CAP, false, dstZone)
        assertEquals(listOf(range.first - 1, range.last + 1), allSamples().map { it.timestampMs })
    }

    @Test fun deletionBoundariesAndProtectionSurviveBackupSerializationAndRestore() = runBlocking {
        open()
        val place = place()
        visit(place, at(-1, 22), at(0, 2), confirmed = false)
        samples(at(-1, 22), at(0, 1))
        manager.deleteDateRange(day, day, EdgeActivityBehavior.CAP, false, zone)
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(VisitEntity.serializer(), db.visitDao().listForPlace(place).single())
        val ranges = db.backupDao().allDeletedRanges().map {
            json.decodeFromString(DeletedTimeRangeEntity.serializer(), json.encodeToString(DeletedTimeRangeEntity.serializer(), it))
        }
        db.backupDao().wipeForRestore()
        db.backupDao().restoreVisits(listOf(json.decodeFromString(VisitEntity.serializer(), encoded)))
        db.backupDao().restoreDeletedRanges(ranges)
        assertTrue(db.visitDao().listForPlace(place).single().stopMerge)
        assertTrue(db.locationSampleDao().insertRecorded(listOf(sample(at(0, 1)))).isEmpty())
    }

    @Test fun repeatedOverlappingDeletionsCoalesceAndKeepExactUntouchedBoundary() = runBlocking {
        open()
        samples(at(2), at(3))
        manager.deleteDateRange(day, day + 1, EdgeActivityBehavior.CAP, false, zone)
        manager.deleteDateRange(day + 1, day + 2, EdgeActivityBehavior.CAP, false, zone)
        assertEquals(listOf(DeletedTimeRangeEntity(at(0), at(3))), db.dataManagementDao().deletedRanges())
        assertEquals(listOf(at(3)), allSamples().map { it.timestampMs })
    }

    @Test fun rangePreviewCountsRemovedAnnotationsButPreservesCappedActivityAnnotations() = runBlocking {
        open()
        val removedPlace = place()
        val removedVisit = visit(removedPlace, at(0, 2), at(0, 3))
        val removedTrip = trip(at(0, 1), at(0, 2))
        val cappedVisit = visit(place("Capped"), at(-1, 23), at(0, 1))
        val targets = listOf(AnnotationTarget.PLACE to removedPlace, AnnotationTarget.VISIT to removedVisit,
            AnnotationTarget.TRIP to removedTrip, AnnotationTarget.VISIT to cappedVisit)
        db.backupDao().restoreConcepts(listOf(ConceptEntity(id = 1, canonicalName = "group", displayName = "Group",
            createdAtMs = 0, updatedAtMs = 0)))
        for ((type, id) in targets) {
            annotations.saveEdits(type, id, "Note", listOf("tag"))
            db.annotationDao().upsert(AnnotationEntity(targetType = type, targetId = id, kind = AnnotationKind.MEMORY,
                content = MemoryMap.encode(mapOf("a" to MemoryEntry("1"), "b" to MemoryEntry("2"))), updatedAtMs = 0))
            db.conceptDao().addMember(ConceptMemberEntity(1, type, id, 0))
        }
        samples(at(-1, 23), at(0, 1), at(0, 2), at(0, 3), at(1))
        val preview = manager.previewDateRange(day, day, EdgeActivityBehavior.CAP, true, zone)
        assertEquals(DataDeletionSummary(3, 1, 1, 1, cappedActivities = 1,
            notes = 3, memories = 6, tagLinks = 3, groupMemberships = 3), preview)
        assertEquals(5L, db.locationSampleDao().count())
        assertEquals(8, db.backupDao().allAnnotations().size)
        assertTrue(db.dataManagementDao().deletedRanges().isEmpty())
        manager.deleteDateRange(day, day, EdgeActivityBehavior.CAP, true, zone)
        assertEquals(2L, db.locationSampleDao().count())
        assertEquals(2, db.backupDao().allAnnotations().size)
        assertEquals("Note", annotations.getNote(AnnotationTarget.VISIT, cappedVisit))
        assertEquals(1, db.backupDao().allConceptMembers().size)
        assertEquals(1, db.backupDao().allEntityTags().size)
    }

    @Test fun placePreviewIncludesPlaceAnnotationsAndChangesWithConnectedTripOption() = runBlocking {
        open()
        val place = place()
        val visit = visit(place, at(0, 2), at(0, 3))
        val trip = trip(at(0, 1), at(0, 2), to = visit)
        annotations.setNote(AnnotationTarget.PLACE, place, "Place note")
        annotations.setNote(AnnotationTarget.TRIP, trip, "Trip note")
        samples(at(0, 1), at(0, 2), at(0, 3))
        val withoutTrips = manager.previewPlace(place, DeletePlaceOptions(false), zone)
        assertEquals(1, withoutTrips.notes)
        assertEquals(0, withoutTrips.trips)
        assertEquals(2L, withoutTrips.samples)
        val withTrips = manager.previewPlace(place, DeletePlaceOptions(), zone)
        assertEquals(2, withTrips.notes)
        assertEquals(1, withTrips.trips)
        assertEquals(3L, withTrips.samples)
        assertEquals(1, withTrips.places)
    }

    @Test fun connectedTripsIncludesConsecutiveUnlinkedLegsAndStopsAtNeighborVisits() = runBlocking {
        open()
        val targetPlace = place()
        val otherPlace = place("Other")
        visit(otherPlace, at(0), at(0, 1))
        visit(targetPlace, at(0, 5), at(0, 6))
        visit(otherPlace, at(0, 9), at(0, 10))
        val outsideBefore = trip(at(-1, 22), at(-1, 23))
        val outsideAfter = trip(at(0, 11), at(0, 12))
        val legs = listOf(2L, 3L, 4L, 6L, 7L, 8L).map { hour -> trip(at(0, hour), at(0, hour + 1)) }
        legs.forEach { annotations.setNote(AnnotationTarget.TRIP, it, "Leg") }
        samples(at(-1, 23), at(0, 2), at(0, 4), at(0, 5), at(0, 7), at(0, 8), at(0, 11))
        val preview = manager.previewPlace(targetPlace, DeletePlaceOptions(), zone)
        assertEquals(6, preview.trips)
        assertEquals(6, preview.notes)
        manager.deletePlace(targetPlace, DeletePlaceOptions(), zone)
        assertNotNull(db.tripDao().byId(outsideBefore))
        assertNotNull(db.tripDao().byId(outsideAfter))
        assertEquals(2, db.tripDao().count())
        assertEquals(2, db.visitDao().count())
        assertTrue(db.backupDao().allAnnotations().isEmpty())
        assertEquals(listOf(at(-1, 23), at(0, 11)), allSamples().map { it.timestampMs })
    }

    @Test fun resetPreviewIncludesGroupAnnotationsAndDefinitionsWithoutDeleting() = runBlocking {
        open()
        db.backupDao().restoreConcepts(listOf(ConceptEntity(id = 1, canonicalName = "group", displayName = "Group",
            createdAtMs = 0, updatedAtMs = 0)))
        annotations.saveEdits(AnnotationTarget.CONCEPT, 1, "Group note", listOf("tag"))
        val preview = manager.previewReset()
        assertEquals(DataDeletionSummary(0, 0, 0, 0, notes = 1, tagLinks = 1, tags = 1, groups = 1), preview)
        assertEquals(1, db.backupDao().allConcepts().size)
    }

    @Test fun futureOnlyDateRangeIsRejectedAndCrossingRangeStopsAtToday() = runBlocking {
        open()
        val today = LocalDate.now(zone).toEpochDay()
        val todayStart = TimeBuckets.dayRangeMillis(today, zone).first
        val tomorrow = TimeBuckets.dayRangeMillis(today + 1, zone).first
        samples(todayStart, tomorrow)
        try {
            manager.deleteDateRange(today + 1, today + 2, EdgeActivityBehavior.CAP, false, zone)
            fail("future days accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(2L, db.locationSampleDao().count())
        assertTrue(db.dataManagementDao().deletedRanges().isEmpty())
        val before = System.currentTimeMillis()
        manager.deleteDateRange(today, today + 2, EdgeActivityBehavior.CAP, false, zone)
        assertEquals(listOf(tomorrow), allSamples().map { it.timestampMs })
        val boundary = db.dataManagementDao().deletedRanges().single().endMs
        assertTrue(boundary in before..System.currentTimeMillis())
        assertEquals(1, db.locationSampleDao().insertRecorded(listOf(sample(boundary))).size)
    }

    @Test fun importingFutureDeletionRangesClampsAtRestoreAndDiscardsFutureOnlyAndInvalidRanges() = runBlocking {
        open()
        val before = System.currentTimeMillis()
        db.backupDao().restoreDeletedRanges(listOf(DeletedTimeRangeEntity(at(0), Long.MAX_VALUE),
            DeletedTimeRangeEntity(before + 86_400_000, Long.MAX_VALUE), DeletedTimeRangeEntity(2, 1)))
        val restored = db.dataManagementDao().deletedRanges().single()
        assertEquals(at(0), restored.startMs)
        assertTrue(restored.endMs in before..System.currentTimeMillis())
        assertTrue(db.locationSampleDao().insertRecorded(listOf(sample(at(0)))).isEmpty())
        assertEquals(1, db.locationSampleDao().insertRecorded(listOf(sample(restored.endMs))).size)
    }

    @Test fun usingBadStoredFutureRangeRepairsItOnceInsteadOfBlockingEachNewSample() = runBlocking {
        open()
        val before = System.currentTimeMillis()
        db.dataManagementDao().insertDeletedRanges(listOf(DeletedTimeRangeEntity(at(0), Long.MAX_VALUE),
            DeletedTimeRangeEntity(before + 86_400_000, Long.MAX_VALUE)))
        val repaired = db.locationSampleDao().deletedRanges(Long.MIN_VALUE, Long.MAX_VALUE).single()
        assertTrue(repaired.endMs in before..System.currentTimeMillis())
        assertEquals(listOf(repaired), db.dataManagementDao().deletedRanges())
        assertEquals(1, db.locationSampleDao().insertRecorded(listOf(sample(repaired.endMs))).size)
        assertEquals(listOf(repaired), db.locationSampleDao().deletedRanges(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    private suspend fun place(name: String = "Place") = db.placeDao().insert(PlaceEntity(
        name = name, latitude = 1.3, longitude = 103.8, radiusMeters = 50.0, category = null,
        source = PlaceSource.USER, googlePlaceId = null, address = null, confirmed = true,
        createdAtMs = 0, coordinateState = PlaceCoordinateState.WGS84_CANONICAL,
    ))
    private suspend fun visit(place: Long, start: Long, end: Long, confirmed: Boolean = true) = db.visitDao().insert(
        VisitEntity(placeId = place, candidateName = null, candidateGooglePlaceId = null,
            candidateLatitude = null, candidateLongitude = null, startMs = start, endMs = end,
            dayEpoch = TimeBuckets.dayEpoch(start, zone), centroidLatitude = 1.3, centroidLongitude = 103.8,
            radiusMeters = 50.0, confirmed = confirmed, confidence = 1f, isOngoing = false))
    private suspend fun trip(start: Long, end: Long, from: Long? = null, to: Long? = null) = db.tripDao().insert(
        TripEntity(fromVisitId = from, toVisitId = to, startMs = start, endMs = end,
            dayEpoch = TimeBuckets.dayEpoch(start, zone), mode = TransportMode.WALKING, modeConfidence = 1f,
            encodedPolyline = Geo.encodePolyline(listOf(1.3 to 103.8, 1.31 to 103.81)), distanceMeters = 100.0, confirmed = false))
    private suspend fun samples(vararg times: Long) { db.locationSampleDao().insertAll(times.map(::sample)) }
    private suspend fun allSamples() = db.locationSampleDao().range(Long.MIN_VALUE, Long.MAX_VALUE)
    private fun sample(time: Long) = LocationSampleEntity(timestampMs = time, dayEpoch = TimeBuckets.dayEpoch(time, zone),
        latitude = 1.3, longitude = 103.8, altitude = null, accuracy = 5f, verticalAccuracyMeters = null,
        bearing = null, bearingAccuracyDegrees = null, speed = null, speedAccuracyMetersPerSecond = null,
        provider = null, isMock = false, elapsedRealtimeNanos = 0, satelliteCount = null, batteryPct = null,
        isCharging = null, networkTransport = null, networkTypeName = null, cellSignalDbm = null,
        hasCellService = null, wifiSsid = null, wifiBssid = null, screenOn = null, arActivity = null,
        arConfidence = null, devicePhysicalState = DevicePhysicalState.STATIONARY, devicePhysicalStateConfidence = 1f)
}
