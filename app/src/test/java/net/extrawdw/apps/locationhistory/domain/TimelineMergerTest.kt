package net.extrawdw.apps.locationhistory.domain

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table tests for [TimelineMerger] over the in-memory DAO fakes (backlog #7's highest-value
 * slice). Pins the invariants the June 2026 redesign established and that historically regressed:
 * adjacent-merge eligibility, fold ordering (the OLDER row's id survives and its content leads),
 * confirmed-only annotation folds, drift-trip removal, and idempotent re-merge — the torn-write
 * self-heal claim of the design doc, previously unproven by tests.
 */
class TimelineMergerTest {

    private val t0 = 1_750_000_000_000L
    private val hour = 3_600_000L
    private val span = t0 - hour to t0 + 24 * hour

    private val visitDao = FakeVisitDao()
    private val tripDao = FakeTripDao(visitDao)
    private val sampleDao = FakeLocationSampleDao()
    private val store = AnnotationStore(FakeTagDao(), FakeAnnotationDao(), FakeConceptDao())
    private val merger = TimelineMerger(visitDao, tripDao, sampleDao, store)

    private fun merge() = runBlocking { merger.merge(span.first, span.second) }

    private fun visit(
        id: Long = 0,
        start: Long,
        end: Long,
        placeId: Long? = 1L,
        candidateName: String? = null,
        confirmed: Boolean = true,
        lat: Double = 1.0,
        lng: Double = 1.0,
        radius: Double = 40.0,
        confidence: Float = 0.8f,
        ongoing: Boolean = false,
    ) = VisitEntity(
        id = id, placeId = placeId, candidateName = candidateName, candidateGooglePlaceId = null,
        candidateLatitude = null, candidateLongitude = null, startMs = start, endMs = end,
        dayEpoch = 0, centroidLatitude = lat, centroidLongitude = lng, radiusMeters = radius,
        confirmed = confirmed, confidence = confidence, isOngoing = ongoing,
    )

    /** A real movement: ~1.5 km straight line, so it never reads as empty or drift. */
    private fun trip(
        id: Long = 0,
        start: Long,
        end: Long,
        mode: TransportMode = TransportMode.WALKING,
        confirmed: Boolean = false,
        modeConfidence: Float = 0.5f,
        fromVisitId: Long? = null,
        toVisitId: Long? = null,
        points: List<Pair<Double, Double>> = listOf(1.0 to 1.0, 1.0 to 1.014),
    ) = TripEntity(
        id = id, fromVisitId = fromVisitId, toVisitId = toVisitId, startMs = start, endMs = end,
        dayEpoch = 0, mode = mode, modeConfidence = modeConfidence,
        encodedPolyline = Geo.encodePolyline(points),
        distanceMeters = Geo.pathLengthMeters(points), confirmed = confirmed,
    )

    // ---- adjacent same-place visit merges --------------------------------------------------------

    @Test
    fun overlappingSamePlaceVisits_mergeToOne_olderIdSurvives() {
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + 2 * hour, lat = 1.0, lng = 1.0),
            visit(id = 2, start = t0 + 2 * hour, end = t0 + 3 * hour, lat = 2.0, lng = 2.0, confidence = 0.9f),
        )
        merge()
        val v = visitDao.visits.single()
        assertEquals(1L, v.id)
        assertEquals(t0, v.startMs)
        assertEquals(t0 + 3 * hour, v.endMs)
        // Duration-weighted centroid: 2h at (1,1) + 1h at (2,2) -> (4/3, 4/3).
        assertEquals(4.0 / 3.0, v.centroidLatitude, 1e-9)
        assertEquals(0.9f, v.confidence)
    }

    @Test
    fun samePlaceVisitsWithGapAndNoTripBetween_merge() {
        // 30 min gap: within MAX_EVIDENCE_GAP_MS, so the no-trip bridge applies.
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + hour + 30 * 60_000L, end = t0 + 3 * hour),
        )
        merge()
        assertEquals(listOf(1L), visitDao.visits.map { it.id })
    }

    @Test
    fun samePlaceVisitsAcrossLongNoTripGap_stayApart() {
        // No trip between them, but the 2h sample-free gap exceeds MAX_EVIDENCE_GAP_MS: presence
        // across the gap is not assumed, so the two real stays must not be welded into one.
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + 3 * hour, end = t0 + 4 * hour),
        )
        merge()
        assertEquals(2, visitDao.visits.size)
    }

    @Test
    fun samePlaceVisitsWithRealTripBetween_stayApart() {
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + 2 * hour, end = t0 + 3 * hour),
        )
        // A real (long) movement between them — and away from drift: different bounding logic
        // doesn't apply because the trip is confirmed.
        tripDao.seed(trip(id = 1, start = t0 + hour, end = t0 + 2 * hour, confirmed = true))
        merge()
        assertEquals(2, visitDao.visits.size)
    }

    @Test
    fun differentPlaces_neverMerge() {
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour, placeId = 1),
            visit(id = 2, start = t0 + hour, end = t0 + 2 * hour, placeId = 2),
        )
        merge()
        assertEquals(2, visitDao.visits.size)
    }

    @Test
    fun candidateNameVisits_mergeOnEqualName_notOnNull() {
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour, placeId = null, candidateName = "Cafe X", confirmed = false),
            visit(id = 2, start = t0 + hour, end = t0 + 2 * hour, placeId = null, candidateName = "Cafe X", confirmed = false),
            visit(id = 3, start = t0 + 3 * hour, end = t0 + 4 * hour, placeId = null, candidateName = null, confirmed = false),
            visit(id = 4, start = t0 + 4 * hour, end = t0 + 5 * hour, placeId = null, candidateName = null, confirmed = false),
        )
        merge()
        // The named pair fused; the two null-name unconfirmed stops did not.
        assertEquals(listOf(1L, 3L, 4L), visitDao.visits.map { it.id }.sorted())
    }

    @Test
    fun chainOfThreeSamePlaceVisits_collapsesFully() {
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + hour, end = t0 + 2 * hour),
            visit(id = 3, start = t0 + 2 * hour, end = t0 + 3 * hour),
        )
        merge()
        val v = visitDao.visits.single()
        assertEquals(1L, v.id)
        assertEquals(t0 to t0 + 3 * hour, v.startMs to v.endMs)
    }

    // ---- annotation folds --------------------------------------------------------------------

    @Test
    fun mergeFoldsDyingVisitAnnotations_ontoOlderSurvivor_oldestFirst() = runBlocking {
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + hour, end = t0 + 2 * hour),
        )
        store.setNote(AnnotationTarget.VISIT, 1, "older note")
        store.setNote(AnnotationTarget.VISIT, 2, "newer note")
        store.applyTag(AnnotationTarget.VISIT, 1, "shared")
        store.applyTag(AnnotationTarget.VISIT, 2, "shared")
        store.applyTag(AnnotationTarget.VISIT, 2, "only-b")
        store.putMemory(AnnotationTarget.VISIT, 2, "k", "v")

        merge()

        // Survivor (older) precedes the dying row's text; tags union; memories move over.
        assertEquals("older note\n\nnewer note", store.getNote(AnnotationTarget.VISIT, 1))
        assertEquals(
            listOf("only-b", "shared"),
            store.tagsFor(AnnotationTarget.VISIT, 1).map { it.displayName }.sorted(),
        )
        assertEquals("v", store.getMemories(AnnotationTarget.VISIT, 1)["k"]?.value)
        // Nothing remains keyed to the dying id.
        assertNull(store.getNote(AnnotationTarget.VISIT, 2))
        assertTrue(store.tagsFor(AnnotationTarget.VISIT, 2).isEmpty())
    }

    @Test
    fun unconfirmedDyingVisit_isMergedWithoutFoldLookup() = runBlocking {
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour, placeId = null, candidateName = "X", confirmed = false),
            visit(id = 2, start = t0 + hour, end = t0 + 2 * hour, placeId = null, candidateName = "X", confirmed = false),
        )
        merge()
        assertEquals(listOf(1L), visitDao.visits.map { it.id })
        // Only confirmed rows can carry annotations, so no fold ran and nothing appeared on 1.
        assertNull(store.getNote(AnnotationTarget.VISIT, 1))
    }

    // ---- adjacent same-mode trip merges ----------------------------------------------------------

    @Test
    fun sameModeTripsWithinGap_fuse_olderIdSurvives() {
        val a = trip(
            id = 1, start = t0, end = t0 + hour, modeConfidence = 0.4f, fromVisitId = 10,
            points = listOf(1.0 to 1.0, 1.0 to 1.014),
        )
        val b = trip(
            id = 2, start = t0 + hour + 60_000, end = t0 + 2 * hour, modeConfidence = 0.7f,
            toVisitId = 20, confirmed = true, points = listOf(1.0 to 1.014, 1.0 to 1.028),
        )
        tripDao.seed(a, b)
        merge()
        val t = tripDao.trips.single()
        assertEquals(1L, t.id)
        assertEquals(t0, t.startMs)
        assertEquals(t0 + 2 * hour, t.endMs)
        assertTrue(t.confirmed)
        assertEquals(0.7f, t.modeConfidence)
        assertEquals(20L, t.toVisitId)
        assertEquals(10L, t.fromVisitId)
        // Geometry is the two paths chained end-to-end.
        val expected = Geo.pathLengthMeters(
            Geo.decodePolyline(a.encodedPolyline) + Geo.decodePolyline(b.encodedPolyline),
        )
        assertEquals(expected, t.distanceMeters, 0.01)
    }

    @Test
    fun differentModeTrips_stayApart() {
        tripDao.seed(
            trip(id = 1, start = t0, end = t0 + hour, mode = TransportMode.WALKING),
            trip(id = 2, start = t0 + hour, end = t0 + 2 * hour, mode = TransportMode.BUS),
        )
        merge()
        assertEquals(2, tripDao.trips.size)
    }

    @Test
    fun tripsWithVisitBetween_stayApart() {
        tripDao.seed(
            trip(id = 1, start = t0, end = t0 + hour),
            trip(id = 2, start = t0 + 2 * hour, end = t0 + 3 * hour),
        )
        visitDao.seed(visit(id = 1, start = t0 + hour, end = t0 + 2 * hour, placeId = 5))
        merge()
        assertEquals(2, tripDao.trips.size)
    }

    @Test
    fun tripsBeyondMergeGap_stayApart() {
        tripDao.seed(
            trip(id = 1, start = t0, end = t0 + hour),
            trip(id = 2, start = t0 + hour + Constants.MERGE_GAP_MS + 1, end = t0 + 3 * hour),
        )
        merge()
        assertEquals(2, tripDao.trips.size)
    }

    // ---- empty & drift trips ---------------------------------------------------------------------

    @Test
    fun emptyUnconfirmedTrips_removed_confirmedKept() {
        tripDao.seed(
            TripEntity(
                id = 1, fromVisitId = null, toVisitId = null, startMs = t0, endMs = t0 + hour,
                dayEpoch = 0, mode = TransportMode.WALKING, modeConfidence = 0.5f,
                encodedPolyline = "", distanceMeters = 0.0, confirmed = false,
            ),
            TripEntity(
                id = 2, fromVisitId = null, toVisitId = null, startMs = t0 + 2 * hour, endMs = t0 + 3 * hour,
                dayEpoch = 0, mode = TransportMode.WALKING, modeConfidence = 0.5f,
                encodedPolyline = "", distanceMeters = 0.0, confirmed = true,
            ),
        )
        merge()
        assertEquals(listOf(2L), tripDao.trips.map { it.id })
    }

    @Test
    fun driftTripBetweenSamePlaceVisits_removedAndVisitsFused() {
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + hour + 600_000, end = t0 + 3 * hour),
        )
        // Net displacement ~33 m (< 80 m drift threshold) but non-zero distance, unconfirmed.
        tripDao.seed(
            trip(
                id = 1, start = t0 + hour, end = t0 + hour + 600_000,
                points = listOf(1.0 to 1.0, 1.0001 to 1.0003, 1.0 to 1.0003),
            ),
        )
        merge()
        assertTrue(tripDao.trips.isEmpty())
        assertEquals(listOf(1L), visitDao.visits.map { it.id })
        assertEquals(t0 to t0 + 3 * hour, visitDao.visits.single().let { it.startMs to it.endMs })
    }

    @Test
    fun consecutiveDriftTrips_bothRemoved_visitsCollapseToOne() {
        // A - drift - A - drift - A: the second trip's bounds must be checked against the
        // already-fused first pair, so the pass's visit snapshot has to track each fuse.
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + hour + 600_000, end = t0 + 2 * hour),
            visit(id = 3, start = t0 + 2 * hour + 600_000, end = t0 + 3 * hour),
        )
        val driftPoints = listOf(1.0 to 1.0, 1.0001 to 1.0003, 1.0 to 1.0003)
        tripDao.seed(
            trip(id = 1, start = t0 + hour, end = t0 + hour + 600_000, points = driftPoints),
            trip(id = 2, start = t0 + 2 * hour, end = t0 + 2 * hour + 600_000, points = driftPoints),
        )
        merge()
        assertTrue(tripDao.trips.isEmpty())
        assertEquals(listOf(1L), visitDao.visits.map { it.id })
        assertEquals(t0 to t0 + 3 * hour, visitDao.visits.single().let { it.startMs to it.endMs })
    }

    @Test
    fun longLoopTripWithZeroDisplacement_isNotDrift() {
        // A run / dog walk from home: kilometers of recorded path that starts and ends at the same
        // place. Net displacement ~0, but path length and duration give it away as real movement.
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + 2 * hour, end = t0 + 3 * hour),
        )
        // Out-and-back: ~1.5 km each way, ending where it started.
        tripDao.seed(
            trip(
                id = 1, start = t0 + hour, end = t0 + 2 * hour,
                points = listOf(1.0 to 1.0, 1.0 to 1.014, 1.0 to 1.0),
            ),
        )
        merge()
        assertEquals(1, tripDao.trips.size)
        assertEquals(2, visitDao.visits.size)
    }

    @Test
    fun shortLoopTrip_overDurationCap_isNotDrift() {
        // Jitter-sized path but a long duration: still not drift (e.g. a slow stroll around the block).
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + 2 * hour, end = t0 + 3 * hour),
        )
        tripDao.seed(
            trip(
                id = 1, start = t0 + hour, end = t0 + 2 * hour, // 1h >> DRIFT_TRIP_MAX_DURATION_MS
                points = listOf(1.0 to 1.0, 1.0001 to 1.0003, 1.0 to 1.0003),
            ),
        )
        merge()
        assertEquals(1, tripDao.trips.size)
        assertEquals(2, visitDao.visits.size)
    }

    @Test
    fun driftTrip_withFarAwayBoundingVisits_doesNotFuseThem() {
        // The same-place visits are HOURS away from the jitter trip; deleting it must not weld a
        // whole afternoon into one stay. Non-adjacent bounds leave trip and visits alone.
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + 5 * hour, end = t0 + 6 * hour),
        )
        tripDao.seed(
            trip(
                id = 1, start = t0 + 3 * hour, end = t0 + 3 * hour + 300_000,
                points = listOf(1.0 to 1.0, 1.0001 to 1.0003, 1.0 to 1.0003),
            ),
        )
        merge()
        assertEquals(1, tripDao.trips.size)
        assertEquals(2, visitDao.visits.size)
    }

    @Test
    fun confirmedShortTrip_isNotDrift() {
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + hour + 600_000, end = t0 + 3 * hour),
        )
        tripDao.seed(
            trip(
                id = 1, start = t0 + hour, end = t0 + hour + 600_000, confirmed = true,
                points = listOf(1.0 to 1.0, 1.0001 to 1.0003),
            ),
        )
        merge()
        assertEquals(1, tripDao.trips.size)
        assertEquals(2, visitDao.visits.size)
    }

    // ---- idempotence ------------------------------------------------------------------------------

    @Test
    fun reMerge_ofNormalizedSpan_isNoOp() {
        visitDao.seed(
            visit(id = 1, start = t0, end = t0 + hour),
            visit(id = 2, start = t0 + hour, end = t0 + 2 * hour),
            visit(id = 3, start = t0 + 4 * hour, end = t0 + 5 * hour, placeId = 2, lat = 2.0, lng = 2.0),
        )
        tripDao.seed(
            trip(id = 1, start = t0 + 2 * hour, end = t0 + 3 * hour),
            trip(id = 2, start = t0 + 3 * hour, end = t0 + 4 * hour, confirmed = true,
                points = listOf(1.0 to 1.014, 1.0 to 1.028)),
        )
        runBlocking { store.setNote(AnnotationTarget.VISIT, 2, "note") }

        merge()
        val visitsAfterFirst = visitDao.visits.toList()
        val tripsAfterFirst = tripDao.trips.toList()
        val noteAfterFirst = runBlocking { store.getNote(AnnotationTarget.VISIT, 1) }

        merge()
        // The torn-write self-heal claim: a second pass over an already-normalized span changes
        // nothing — rows, geometry and folded annotations are all stable.
        assertEquals(visitsAfterFirst, visitDao.visits.toList())
        assertEquals(tripsAfterFirst, tripDao.trips.toList())
        assertEquals(noteAfterFirst, runBlocking { store.getNote(AnnotationTarget.VISIT, 1) })
        assertFalse(noteAfterFirst.isNullOrEmpty())
    }
}
