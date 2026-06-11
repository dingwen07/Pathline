package net.extrawdw.apps.locationhistory.api

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantDao
import net.extrawdw.apps.locationhistory.data.db.ApiPlaceGrantEntity
import net.extrawdw.apps.locationhistory.data.db.ConceptEntity
import net.extrawdw.apps.locationhistory.data.db.EntityTagEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.domain.FakeConceptDao
import net.extrawdw.apps.locationhistory.domain.FakePlaceDao
import net.extrawdw.apps.locationhistory.domain.FakeTagDao
import net.extrawdw.apps.locationhistory.domain.FakeTripDao
import net.extrawdw.apps.locationhistory.domain.FakeVisitDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table tests for [ApiGate] — the data API's security kernel, now a plain class over DAO fakes
 * (backlog #2). Pins the rules the on-device consumer suite can only probe end-to-end: permission
 * tiers, extended-history clamp-vs-throw, unconfirmed/aged target invisibility, concept
 * visibility, tag-scope joins, and write gating.
 */
class ApiGateTest {

    private val now = 1_750_000_000_000L
    private val horizon = PathlineContract.EXTENDED_HISTORY_WINDOW_MS
    private val perms = PathlineContract.Permissions

    private val pkg = "com.example.consumer"

    private class RecordingLogger : AccessLogger {
        val events = mutableListOf<AccessEvent>()
        override fun log(caller: Caller, event: AccessEvent) {
            events.add(event)
        }
    }

    private class FakeGrantDao : ApiPlaceGrantDao {
        val grants = mutableListOf<ApiPlaceGrantEntity>()

        override suspend fun insertIgnore(grants: List<ApiPlaceGrantEntity>) {
            for (g in grants) {
                if (this.grants.none { it.packageName == g.packageName && it.placeId == g.placeId }) {
                    this.grants.add(g)
                }
            }
        }

        override suspend fun touch(packageName: String, placeIds: List<Long>, nowMs: Long) {
            grants.replaceAll {
                if (it.packageName == packageName && it.placeId in placeIds.toSet()) {
                    it.copy(lastGrantedMs = nowMs)
                } else {
                    it
                }
            }
        }

        override suspend fun grantedPlaceIds(packageName: String): List<Long> =
            grants.filter { it.packageName == packageName }.map { it.placeId }

        override suspend fun grantedAmong(packageName: String, placeIds: List<Long>): List<Long> =
            grants.filter { it.packageName == packageName && it.placeId in placeIds.toSet() }
                .map { it.placeId }

        override suspend fun isGranted(packageName: String, placeId: Long): Boolean =
            grants.any { it.packageName == packageName && it.placeId == placeId }
    }

    private val visitDao = FakeVisitDao()
    private val tripDao = FakeTripDao(visitDao)
    private val placeDao = FakePlaceDao()
    private val conceptDao = FakeConceptDao()
    private val tagDao = FakeTagDao()
    private val grantDao = FakeGrantDao()
    private val logger = RecordingLogger()
    private var accessOn = true

    private val gate = ApiGate(
        visitDao = visitDao,
        tripDao = tripDao,
        placeDao = placeDao,
        conceptDao = conceptDao,
        tagDao = tagDao,
        grantDao = grantDao,
        accessEnabled = { accessOn },
        logger = logger,
    )

    private fun caller(vararg held: String, pkg: String? = this.pkg): Caller =
        Caller(pkg) { it in held }

    // ---- entity helpers ----------------------------------------------------------------------

    private fun visit(
        id: Long = 0,
        start: Long = now - 3_600_000,
        end: Long = now - 1_800_000,
        confirmed: Boolean = true,
        placeId: Long? = null,
    ) = VisitEntity(
        id = id, placeId = placeId, candidateName = null, candidateGooglePlaceId = null,
        candidateLatitude = null, candidateLongitude = null, startMs = start, endMs = end,
        dayEpoch = 0, centroidLatitude = 0.0, centroidLongitude = 0.0, radiusMeters = 40.0,
        confirmed = confirmed, confidence = 1f, isOngoing = false,
    )

    private fun trip(
        id: Long = 0,
        start: Long = now - 3_600_000,
        end: Long = now - 1_800_000,
        confirmed: Boolean = true,
    ) = TripEntity(
        id = id, fromVisitId = null, toVisitId = null, startMs = start, endMs = end, dayEpoch = 0,
        mode = TransportMode.WALKING, modeConfidence = 1f, encodedPolyline = "", distanceMeters = 100.0,
        confirmed = confirmed,
    )

    private fun place(id: Long = 0, name: String = "Cafe") = PlaceEntity(
        id = id, name = name, latitude = 0.0, longitude = 0.0, radiusMeters = 50.0,
        category = null, source = PlaceSource.USER, googlePlaceId = null, address = null,
        confirmed = true, createdAtMs = now,
    )

    private fun concept(name: String) = ConceptEntity(
        canonicalName = name.lowercase(), displayName = name, createdAtMs = now, updatedAtMs = now,
    )

    private fun grant(placeId: Long, toPkg: String = pkg) {
        grants(placeId, toPkg)
    }

    private fun grants(placeId: Long, toPkg: String) = runBlocking {
        grantDao.insertIgnore(listOf(ApiPlaceGrantEntity(toPkg, placeId, now, now)))
    }

    // ---- requireWindowedRead: tiers + extended history ------------------------------------------

    @Test
    fun windowedRead_passesWithBasePermission_insideHorizon() {
        gate.requireWindowedRead(
            caller(perms.READ_TIMELINE), perms.READ_TIMELINE, "visits",
            now - 1000, now, now, groupId = null,
        )
        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun windowedRead_deniesAndLogsWithoutBasePermission() {
        val e = assertThrows(SecurityException::class.java) {
            gate.requireWindowedRead(
                caller(), perms.READ_TIMELINE, "visits", now - 1000, now, now, groupId = 7L,
            )
        }
        assertTrue(e.message!!.contains(perms.READ_TIMELINE))
        val logged = logger.events.single()
        assertEquals(perms.READ_TIMELINE, logged.deniedPermission)
        assertEquals(0, logged.rowCount)
        assertEquals(7L, logged.groupId)
        assertFalse(logged.isWrite)
        assertTrue(logged.notify)
    }

    @Test
    fun windowedRead_explicitOldStart_throwsWithoutExtendedHistory() {
        val start = now - horizon - 1
        val e = assertThrows(SecurityException::class.java) {
            gate.requireWindowedRead(
                caller(perms.READ_TIMELINE), perms.READ_TIMELINE, "visits", start, now, now, null,
            )
        }
        assertTrue(e.message!!.contains(perms.READ_EXTENDED_HISTORY))
        assertEquals(perms.READ_EXTENDED_HISTORY, logger.events.single().deniedPermission)
    }

    @Test
    fun windowedRead_oldStart_passesWithExtendedHistory() {
        gate.requireWindowedRead(
            caller(perms.READ_TIMELINE, perms.READ_EXTENDED_HISTORY),
            perms.READ_TIMELINE, "visits", 0, now, now, null,
        )
        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun windowedRead_startExactlyAtHorizon_needsNoExtendedHistory() {
        gate.requireWindowedRead(
            caller(perms.READ_TIMELINE), perms.READ_TIMELINE, "visits",
            now - horizon, now, now, null,
        )
        assertTrue(logger.events.isEmpty())
    }

    // ---- access switch -----------------------------------------------------------------------

    @Test
    fun apiDisabled_deniesWithApiPermission_withoutNotify() {
        accessOn = false
        assertThrows(SecurityException::class.java) {
            gate.requireApiEnabled(caller(perms.READ_TIMELINE), "visits", now, now, now, null)
        }
        val logged = logger.events.single()
        assertEquals(perms.API, logged.deniedPermission)
        assertFalse(logged.notify)
    }

    // ---- minVisibleEndMs ---------------------------------------------------------------------

    @Test
    fun minVisibleEnd_clampsToHorizonWithoutExtendedHistory() {
        assertEquals(now - horizon, gate.minVisibleEndMs(caller(), now))
        assertEquals(Long.MIN_VALUE, gate.minVisibleEndMs(caller(perms.READ_EXTENDED_HISTORY), now))
    }

    // ---- targetVisible -----------------------------------------------------------------------

    @Test
    fun placeVisible_onlyWhenGrantedAndExisting() = runBlocking {
        placeDao.seed(place(id = 1))
        val c = caller(perms.READ_TIMELINE)
        assertFalse(gate.targetVisible(c, AnnotationTarget.PLACE, 1, now))
        grant(1)
        assertTrue(gate.targetVisible(c, AnnotationTarget.PLACE, 1, now))
        // A grant whose place row is gone reads as invisible (indistinguishable from missing).
        grant(99)
        assertFalse(gate.targetVisible(c, AnnotationTarget.PLACE, 99, now))
    }

    @Test
    fun placeVisible_readAllPlacesBypassesGrants() = runBlocking {
        placeDao.seed(place(id = 1))
        assertTrue(gate.targetVisible(caller(perms.READ_ALL_PLACES), AnnotationTarget.PLACE, 1, now))
        assertFalse(gate.targetVisible(caller(perms.READ_ALL_PLACES), AnnotationTarget.PLACE, 2, now))
    }

    @Test
    fun visitVisible_requiresConfirmed() = runBlocking {
        visitDao.seed(visit(id = 1, confirmed = true), visit(id = 2, confirmed = false))
        val c = caller(perms.READ_TIMELINE)
        assertTrue(gate.targetVisible(c, AnnotationTarget.VISIT, 1, now))
        assertFalse(gate.targetVisible(c, AnnotationTarget.VISIT, 2, now))
        assertFalse(gate.targetVisible(c, AnnotationTarget.VISIT, 3, now))
    }

    @Test
    fun visitVisible_agedOutWithoutExtendedHistory() = runBlocking {
        visitDao.seed(visit(id = 1, start = now - horizon - 2000, end = now - horizon - 1000))
        assertFalse(gate.targetVisible(caller(), AnnotationTarget.VISIT, 1, now))
        assertTrue(
            gate.targetVisible(caller(perms.READ_EXTENDED_HISTORY), AnnotationTarget.VISIT, 1, now),
        )
    }

    @Test
    fun tripVisible_mirrorsVisitRules() = runBlocking {
        tripDao.seed(
            trip(id = 1, confirmed = true),
            trip(id = 2, confirmed = false),
            trip(id = 3, start = now - horizon - 2000, end = now - horizon - 1000),
        )
        val c = caller(perms.READ_TIMELINE)
        assertTrue(gate.targetVisible(c, AnnotationTarget.TRIP, 1, now))
        assertFalse(gate.targetVisible(c, AnnotationTarget.TRIP, 2, now))
        assertFalse(gate.targetVisible(c, AnnotationTarget.TRIP, 3, now))
    }

    @Test
    fun conceptVisible_wheneverItExists() = runBlocking {
        val id = conceptDao.insertIgnore(concept("Japan Trip"))
        // No permissions at all: concept visibility is existence (the read tier is enforced by
        // requireAnnotationRead, not here).
        assertTrue(gate.targetVisible(caller(), AnnotationTarget.CONCEPT, id, now))
        assertFalse(gate.targetVisible(caller(), AnnotationTarget.CONCEPT, id + 1, now))
    }

    // ---- requireAnnotationRead tiers -----------------------------------------------------------

    @Test
    fun annotationRead_needsReadAnnotationsFirst() {
        val e = assertThrows(SecurityException::class.java) {
            gate.requireAnnotationRead(caller(perms.READ_TIMELINE), AnnotationTarget.VISIT, "tags", now, null)
        }
        assertTrue(e.message!!.contains(perms.READ_ANNOTATIONS))
    }

    @Test
    fun annotationRead_placeTier_acceptsEitherPlaceScope() {
        // READ_TIMELINE works…
        gate.requireAnnotationRead(
            caller(perms.READ_ANNOTATIONS, perms.READ_TIMELINE), AnnotationTarget.PLACE, "tags", now, null,
        )
        // …READ_ALL_PLACES works…
        gate.requireAnnotationRead(
            caller(perms.READ_ANNOTATIONS, perms.READ_ALL_PLACES), AnnotationTarget.PLACE, "tags", now, null,
        )
        // …annotations alone does not.
        val e = assertThrows(SecurityException::class.java) {
            gate.requireAnnotationRead(
                caller(perms.READ_ANNOTATIONS), AnnotationTarget.PLACE, "tags", now, null,
            )
        }
        assertTrue(e.message!!.contains(perms.READ_TIMELINE))
    }

    @Test
    fun annotationRead_conceptTier_needsOnlyReadAnnotations() {
        gate.requireAnnotationRead(
            caller(perms.READ_ANNOTATIONS), AnnotationTarget.CONCEPT, "tags", now, null,
        )
        assertTrue(logger.events.isEmpty())
    }

    // ---- visibleTagIds scoping -----------------------------------------------------------------

    private fun link(tagId: Long, target: AnnotationTarget, targetId: Long) = runBlocking {
        tagDao.link(EntityTagEntity(tagId, target, targetId, now))
    }

    @Test
    fun visibleTags_placeLeg_followsGrantsOrAllPlaces() = runBlocking {
        link(10, AnnotationTarget.PLACE, 1)
        link(11, AnnotationTarget.PLACE, 2)
        grant(1)

        val grantedOnly = gate.visibleTagIds(caller(perms.READ_ANNOTATIONS, perms.READ_TIMELINE), now)
        assertEquals(setOf(10L), grantedOnly)

        val allPlaces = gate.visibleTagIds(caller(perms.READ_ANNOTATIONS, perms.READ_ALL_PLACES), now)
        assertEquals(setOf(10L, 11L), allPlaces)

        // No place scope at all -> no place-attached tags.
        assertEquals(emptySet<Long>(), gate.visibleTagIds(caller(perms.READ_ANNOTATIONS), now))
    }

    @Test
    fun visibleTags_timelineLeg_confirmedAndInsideHorizonOnly() = runBlocking {
        visitDao.seed(
            visit(id = 1, confirmed = true),
            visit(id = 2, confirmed = false),
            visit(id = 3, start = now - horizon - 2000, end = now - horizon - 1000),
        )
        tripDao.seed(trip(id = 1, confirmed = true))
        link(20, AnnotationTarget.VISIT, 1)
        link(21, AnnotationTarget.VISIT, 2)
        link(22, AnnotationTarget.VISIT, 3)
        link(23, AnnotationTarget.TRIP, 1)

        val withTimeline = gate.visibleTagIds(caller(perms.READ_ANNOTATIONS, perms.READ_TIMELINE), now)
        assertEquals(setOf(20L, 23L), withTimeline)

        // The clamp lifts with extended history: the aged visit's tag appears.
        val withExtended = gate.visibleTagIds(
            caller(perms.READ_ANNOTATIONS, perms.READ_TIMELINE, perms.READ_EXTENDED_HISTORY), now,
        )
        assertEquals(setOf(20L, 22L, 23L), withExtended)

        // Without READ_TIMELINE the visit/trip legs vanish entirely.
        assertEquals(emptySet<Long>(), gate.visibleTagIds(caller(perms.READ_ANNOTATIONS), now))
    }

    @Test
    fun visibleTags_conceptLeg_existenceOnly() = runBlocking {
        val id = conceptDao.insertIgnore(concept("Gyms"))
        link(30, AnnotationTarget.CONCEPT, id)
        link(31, AnnotationTarget.CONCEPT, id + 50)
        assertEquals(setOf(30L), gate.visibleTagIds(caller(perms.READ_ANNOTATIONS), now))
    }

    // ---- checkWrite ----------------------------------------------------------------------------

    @Test
    fun checkWrite_deniesWithoutWritePermission() = runBlocking {
        visitDao.seed(visit(id = 1))
        val e = assertThrows(SecurityException::class.java) {
            runBlocking {
                gate.checkWrite(
                    caller(perms.READ_TIMELINE), AnnotationTarget.VISIT, 1,
                    perms.WRITE_ANNOTATIONS, "tags", now, null,
                )
            }
        }
        assertTrue(e.message!!.contains(perms.WRITE_ANNOTATIONS))
        val logged = logger.events.single()
        assertTrue(logged.isWrite)
        assertEquals(perms.WRITE_ANNOTATIONS, logged.deniedPermission)
    }

    @Test
    fun checkWrite_needsTargetReadTier() = runBlocking {
        visitDao.seed(visit(id = 1))
        val e = assertThrows(SecurityException::class.java) {
            runBlocking {
                gate.checkWrite(
                    caller(perms.WRITE_ANNOTATIONS), AnnotationTarget.VISIT, 1,
                    perms.WRITE_ANNOTATIONS, "tags", now, null,
                )
            }
        }
        assertTrue(e.message!!.contains(perms.READ_TIMELINE))
    }

    @Test
    fun checkWrite_invisibleTarget_rejectsIndistinguishably() = runBlocking {
        visitDao.seed(visit(id = 1, confirmed = false))
        val unconfirmed = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                gate.checkWrite(
                    caller(perms.WRITE_ANNOTATIONS, perms.READ_TIMELINE), AnnotationTarget.VISIT, 1,
                    perms.WRITE_ANNOTATIONS, "tags", now, null,
                )
            }
        }
        val missing = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                gate.checkWrite(
                    caller(perms.WRITE_ANNOTATIONS, perms.READ_TIMELINE), AnnotationTarget.VISIT, 99,
                    perms.WRITE_ANNOTATIONS, "tags", now, null,
                )
            }
        }
        // The two failure messages must not differ — ids can't be probed through error text.
        assertEquals(
            unconfirmed.message!!.replace(" 1 ", " N "),
            missing.message!!.replace(" 99 ", " N "),
        )
        // Both rejections were logged as writes that changed nothing, with no denied permission.
        assertEquals(2, logger.events.size)
        assertTrue(logger.events.all { it.isWrite && it.rowCount == 0 && it.deniedPermission == null })
    }

    @Test
    fun checkWrite_passesOnVisibleTarget() = runBlocking {
        visitDao.seed(visit(id = 1))
        gate.checkWrite(
            caller(perms.WRITE_ANNOTATIONS, perms.READ_TIMELINE), AnnotationTarget.VISIT, 1,
            perms.WRITE_ANNOTATIONS, "tags", now, null,
        )
        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun checkWrite_apiOff_deniesQuietly() = runBlocking {
        accessOn = false
        visitDao.seed(visit(id = 1))
        assertThrows(SecurityException::class.java) {
            runBlocking {
                gate.checkWrite(
                    caller(perms.WRITE_ANNOTATIONS, perms.READ_TIMELINE), AnnotationTarget.VISIT, 1,
                    perms.WRITE_ANNOTATIONS, "tags", now, null,
                )
            }
        }
        val logged = logger.events.single()
        assertEquals(perms.API, logged.deniedPermission)
        assertFalse(logged.notify)
        assertTrue(logged.isWrite)
    }

    @Test
    fun conceptCollectionWrite_needsWriteAnnotations() {
        gate.checkConceptCollectionWrite(caller(perms.WRITE_ANNOTATIONS), "concepts", now, null)
        assertThrows(SecurityException::class.java) {
            gate.checkConceptCollectionWrite(caller(), "concepts", now, null)
        }
        assertEquals(perms.WRITE_ANNOTATIONS, logger.events.single().deniedPermission)
    }

    // ---- window & parameter parsing --------------------------------------------------------------

    @Test
    fun requireWindow_startRequired_endDefaultsToNow() {
        assertThrows(IllegalArgumentException::class.java) {
            gate.requireWindow(null, null, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            gate.requireWindow("garbage", null, now)
        }
        assertEquals(now - 5 to now, gate.requireWindow("${now - 5}", null, now))
        assertEquals(1L to 2L, gate.requireWindow("1", "2", now))
        assertThrows(IllegalArgumentException::class.java) {
            gate.requireWindow("5", "5", now)
        }
    }

    @Test
    fun searchWindow_clampsWhenWindowless() {
        assertEquals(
            now - horizon to now,
            gate.searchWindow(caller(), null, null, "visits", now, null),
        )
        assertEquals(
            0L to now,
            gate.searchWindow(caller(perms.READ_EXTENDED_HISTORY), null, null, "visits", now, null),
        )
    }

    @Test
    fun searchWindow_explicitOldStart_throwsWithoutExtendedHistory() {
        val start = now - horizon - 1
        assertThrows(SecurityException::class.java) {
            gate.searchWindow(caller(), "$start", null, "visits", now, null)
        }
        assertEquals(perms.READ_EXTENDED_HISTORY, logger.events.single().deniedPermission)
        // An explicit recent window passes through untouched.
        assertEquals(
            now - 5 to now - 1,
            gate.searchWindow(caller(), "${now - 5}", "${now - 1}", "visits", now, null),
        )
    }

    @Test
    fun parseLimit_validatesAndClamps() {
        assertNull(gate.parseLimit(null))
        assertEquals(5, gate.parseLimit("5"))
        assertEquals(ApiGate.MAX_LIMIT, gate.parseLimit("999999"))
        assertThrows(IllegalArgumentException::class.java) { gate.parseLimit("0") }
        assertThrows(IllegalArgumentException::class.java) { gate.parseLimit("-3") }
        assertThrows(IllegalArgumentException::class.java) { gate.parseLimit("abc") }
    }

    @Test
    fun parseGroup_acceptsOnlyNearNow() {
        assertNull(gate.parseGroup(null, now))
        assertNull(gate.parseGroup("garbage", now))
        assertEquals(now, gate.parseGroup("$now", now))
        // Within the window behind, and the small tolerance ahead.
        assertEquals(
            now - PathlineContract.GROUP_WINDOW_MS,
            gate.parseGroup("${now - PathlineContract.GROUP_WINDOW_MS}", now),
        )
        assertNull(gate.parseGroup("${now - PathlineContract.GROUP_WINDOW_MS - 1}", now))
        assertEquals(now + 5_000, gate.parseGroup("${now + 5_000}", now))
        assertNull(gate.parseGroup("${now + 5_001}", now))
    }

    // ---- Caller --------------------------------------------------------------------------------

    @Test
    fun byMe_threeStateEncoding() {
        val c = caller()
        assertEquals(1, c.byMe(pkg))
        assertEquals(0, c.byMe("com.other.app"))
        assertNull(c.byMe(null))
        // A caller the platform couldn't name never matches an attributed writer.
        assertEquals(0, caller(pkg = null).byMe(pkg))
    }

    @Test
    fun routeUnlocked_eitherRoutePermissionOrFullHistory() {
        assertFalse(caller(perms.READ_TIMELINE).routeUnlocked())
        assertTrue(caller(perms.READ_TIMELINE_ROUTES).routeUnlocked())
        assertTrue(caller(perms.READ_LOCATION_HISTORY).routeUnlocked())
    }
}
