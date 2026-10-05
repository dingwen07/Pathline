package net.extrawdw.apps.locationhistory.data.repo

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.ConceptEntity
import net.extrawdw.apps.locationhistory.data.db.ConceptMemberEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceMergerTest {
    private lateinit var db: AppDatabase
    private lateinit var annotations: AnnotationStore
    private lateinit var merger: PlaceMerger
    private val target = AnnotationTarget.PLACE

    private fun open(failDelete: Boolean = false) {
        System.loadLibrary("sqlcipher")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .setDriver(SQLCipherDriver("place-merge-test-only".toByteArray(), null, null))
            .addCallback(object : RoomDatabase.Callback() {
                override suspend fun onCreate(connection: SQLiteConnection) {
                    AppDatabase.DIRTY_TRIGGERS.forEach(connection::execSQL)
                    if (failDelete) connection.execSQL(
                        "CREATE TRIGGER reject_place_delete BEFORE DELETE ON places BEGIN " +
                            "SELECT RAISE(ABORT, 'test rollback'); END",
                    )
                }
            }).build()
        annotations = AnnotationStore(db.tagDao(), db.annotationDao(), db.conceptDao())
        merger = PlaceMerger(db, annotations, TimelineWriteLock())
    }

    @After
    fun close() { if (::db.isInitialized) db.close() }

    @Test
    fun transfersAllVisitsAndAnnotationsWithoutChangingDestinationOrTripReferences() = runBlocking {
        open()
        val destinationId = db.placeDao().insert(place("Destination", fixed = true))
        val sourceId = db.placeDao().insert(place("Source"))
        val destination = db.placeDao().byId(destinationId)
        val confirmed = visit(sourceId, confirmed = true)
        val pending = visit(sourceId, confirmed = false).copy(startMs = 200_000, endMs = 300_000, isOngoing = true)
        val confirmedId = db.visitDao().insert(confirmed)
        val pendingId = db.visitDao().insert(pending)
        val existingId = db.visitDao().insert(visit(destinationId, true))
        val trip = TripEntity(fromVisitId = confirmedId, toVisitId = pendingId, startMs = 60_000,
            endMs = 200_000, dayEpoch = 0, mode = TransportMode.WALKING, modeConfidence = 1f,
            encodedPolyline = "", distanceMeters = 0.0, confirmed = true)
        val tripId = db.tripDao().insert(trip)
        annotations.saveEdits(target, destinationId, "  destination \n", listOf("shared", "kept"))
        annotations.saveEdits(target, sourceId, " \nsource  ", listOf("shared", "added"))
        annotations.putMemory(target, destinationId, "key", "one ")
        annotations.putMemory(target, sourceId, "key", " two")
        annotations.putMemory(target, sourceId, "new", "original")
        annotations.setNote(AnnotationTarget.VISIT, confirmedId, "visit note")
        val conceptId = db.conceptDao().insertIgnore(ConceptEntity(canonicalName = "group",
            displayName = "Group", createdAtMs = 0, updatedAtMs = 0))
        db.conceptDao().addMember(ConceptMemberEntity(conceptId, target, sourceId, 0))
        db.conceptDao().addMember(ConceptMemberEntity(conceptId, target, destinationId, 0))
        db.backupDao().clearAllDirty()

        assertTrue(merger.merge(sourceId, destinationId))

        assertNull(db.placeDao().byId(sourceId))
        assertEquals(destination, db.placeDao().byId(destinationId))
        assertEquals(confirmed.copy(id = confirmedId, placeId = destinationId), db.visitDao().byId(confirmedId))
        assertEquals(pending.copy(id = pendingId, placeId = destinationId), db.visitDao().byId(pendingId))
        assertEquals(destinationId, db.visitDao().byId(existingId)?.placeId)
        assertEquals(trip.copy(id = tripId), db.tripDao().byId(tripId))
        assertEquals("visit note", annotations.getNote(AnnotationTarget.VISIT, confirmedId))
        assertEquals("  destination\nsource  ", annotations.getNote(target, destinationId))
        assertEquals(setOf("shared", "kept", "added"), annotations.tagsFor(target, destinationId).map { it.displayName }.toSet())
        assertEquals("one\ntwo", annotations.getMemories(target, destinationId)["key"]?.value)
        assertEquals("original", annotations.getMemories(target, destinationId)["new"]?.value)
        assertNull(annotations.getNote(target, sourceId))
        assertTrue(annotations.tagsFor(target, sourceId).isEmpty())
        assertTrue(annotations.getMemories(target, sourceId).isEmpty())
        assertEquals(listOf(destinationId), db.conceptDao().membersOf(conceptId).map { it.targetId })
        assertTrue(db.backupDao().allDirty().any { it.stream == "visits" })
        assertFalse(merger.merge(sourceId, destinationId)) // stale/double submission cannot append twice
    }

    @Test
    fun failedDeleteRollsBackVisitMovesAndAllAnnotationChanges() = runBlocking {
        open(failDelete = true)
        val destination = db.placeDao().insert(place("Destination"))
        val source = db.placeDao().insert(place("Source"))
        val visitId = db.visitDao().insert(visit(source, true))
        annotations.saveEdits(target, destination, "destination", listOf("kept"))
        annotations.saveEdits(target, source, "source", listOf("added"))
        annotations.putMemory(target, source, "memory", "source")
        val beforeDestination = annotations.loadEdits(target, destination)
        val beforeSource = annotations.loadEdits(target, source)
        assertTrue(runCatching { merger.merge(source, destination) }.isFailure)
        assertNotNull(db.placeDao().byId(source))
        assertEquals(source, db.visitDao().byId(visitId)?.placeId)
        assertEquals(beforeDestination, annotations.loadEdits(target, destination))
        assertEquals(beforeSource, annotations.loadEdits(target, source))
    }

    @Test
    fun rejectsSelfAndMissingDestinationWithoutChangingSource() = runBlocking {
        open()
        val source = db.placeDao().insert(place("Source"))
        annotations.setNote(target, source, "unchanged")
        assertFalse(merger.merge(source, source))
        assertFalse(merger.merge(source, source + 1))
        assertNotNull(db.placeDao().byId(source))
        assertEquals("unchanged", annotations.getNote(target, source))
    }

    @Test
    fun ignoredPairPersistsAcrossRepositoryInstancesInEitherOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = place("First").copy(id = 10, createdAtMs = System.currentTimeMillis())
        val second = place("Second").copy(id = 20)
        SettingsRepository(context).ignorePlaceMergePair(first, second)
        val reloaded = SettingsRepository(context).ignoredPlaceMergePairs.first()
        assertTrue(placeMergePairKey(second, first) in reloaded)
    }

    private fun place(name: String, fixed: Boolean = false) = PlaceEntity(
        name = name, latitude = 1.3, longitude = 103.8, radiusMeters = 50.0, category = null,
        source = PlaceSource.USER, googlePlaceId = null, address = "Address", confirmed = true,
        createdAtMs = 1, fixed = fixed, coordinateState = PlaceCoordinateState.WGS84_CANONICAL,
    )

    private fun visit(placeId: Long, confirmed: Boolean) = VisitEntity(
        placeId = placeId, candidateName = null, candidateGooglePlaceId = null,
        candidateLatitude = null, candidateLongitude = null, startMs = 0, endMs = 60_000,
        dayEpoch = 0, centroidLatitude = 1.3, centroidLongitude = 103.8, radiusMeters = 50.0,
        confirmed = confirmed, confidence = .7f, isOngoing = false,
    )
}
