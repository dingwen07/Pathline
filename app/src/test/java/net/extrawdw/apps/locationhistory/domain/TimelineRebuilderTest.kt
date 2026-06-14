package net.extrawdw.apps.locationhistory.domain

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.data.repo.RecordingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario tests for [TimelineRebuilder] over the in-memory fakes — the rebuild half of the
 * June 2026 redesign, previously only field-debuggable inside the worker. Pins: stay detection ->
 * visit materialization -> trip filling end to end, the overnight-stay single-row rule, confirmed
 * rows as ground truth (no re-detection on top of them, stubs don't swallow the rest of a gap),
 * the single-ongoing-visit clock, the in-progress trip, dangling-endpoint repair, and idempotent
 * re-runs.
 */
class TimelineRebuilderTest {

    // A fixed local day; all instants are derived from TimeBuckets so the test is zone-stable.
    private val day = TimeBuckets.dayEpoch(1_750_000_000_000L)
    private val dayStart = TimeBuckets.dayRangeMillis(day).first
    private fun at(minutes: Long) = dayStart + minutes * 60_000L
    private var nowMs = at(23 * 60)

    private val visitDao = FakeVisitDao()
    private val tripDao = FakeTripDao(visitDao)
    private val sampleDao = FakeLocationSampleDao()
    private val placeDao = FakePlaceDao()
    private val store = AnnotationStore(FakeTagDao(), FakeAnnotationDao(), FakeConceptDao())

    private var matchPlace: suspend (Double, Double) -> PlaceMatch = { _, _ -> PlaceMatch.None }

    /** One single-mode WALKING run per non-trivial sample span — the segmenter seam scripted. */
    private val segmentTrips: (List<LocationSampleEntity>) -> List<SegmentResult> = { samples ->
        if (samples.size < 2) emptyList()
        else {
            val points = samples.map { it.latitude to it.longitude }
            listOf(
                SegmentResult(
                    startMs = samples.first().timestampMs,
                    endMs = samples.last().timestampMs,
                    mode = TransportMode.WALKING,
                    confidence = 0.7f,
                    encodedPolyline = Geo.encodePolyline(points),
                    distanceMeters = Geo.pathLengthMeters(points),
                ),
            )
        }
    }

    private val rebuilder = TimelineRebuilder(
        sampleDao = sampleDao,
        visitDao = visitDao,
        tripDao = tripDao,
        locationRepository = LocationRepository(sampleDao),
        recordingRepository = RecordingRepository(tripDao),
        placeRepository = PlaceRepository(placeDao, visitDao, store),
        visitDetector = VisitDetector(),
        merger = TimelineMerger(visitDao, tripDao, sampleDao, placeDao, store),
        matchPlace = { lat, lon -> matchPlace(lat, lon) },
        segmentTrips = segmentTrips,
        inTransaction = { block -> block() },
        now = { nowMs },
        log = {},
    )

    private fun rebuild(d: Long = day) = runBlocking { rebuilder.rebuildDay(d) }

    // ---- sample seeding ---------------------------------------------------------------------

    private fun sample(
        tMs: Long,
        lat: Double,
        lon: Double = -74.0,
        state: DevicePhysicalState,
        speed: Float,
        accuracy: Float = 10f,
    ) = runBlocking {
        sampleDao.insert(
            LocationSampleEntity(
                timestampMs = tMs, dayEpoch = TimeBuckets.dayEpoch(tMs),
                latitude = lat, longitude = lon, altitude = null, accuracy = accuracy,
                verticalAccuracyMeters = null, bearing = null, bearingAccuracyDegrees = null,
                speed = speed, speedAccuracyMetersPerSecond = null, provider = "fused",
                isMock = false, elapsedRealtimeNanos = 0, satelliteCount = null, batteryPct = null,
                isCharging = null, networkTransport = null, networkTypeName = null,
                cellSignalDbm = null, hasCellService = null, wifiSsid = null, wifiBssid = null,
                screenOn = null, arActivity = null, arConfidence = null,
                devicePhysicalState = state, devicePhysicalStateConfidence = 1f,
            ),
        )
    }

    /** One stationary fix per minute over [fromMin, toMin], all at ([lat], lon). */
    private fun stay(fromMin: Long, toMin: Long, lat: Double, stepMin: Long = 1) {
        for (m in fromMin..toMin step stepMin) {
            sample(at(m), lat, state = DevicePhysicalState.STATIONARY, speed = 0f)
        }
    }

    /** One moving fix per minute, latitude advancing 0.001 deg (~111 m) per minute from [fromLat]. */
    private fun walk(fromMin: Long, toMin: Long, fromLat: Double) {
        for ((k, m) in (fromMin..toMin).withIndex()) {
            sample(at(m), fromLat + k * 0.001, state = DevicePhysicalState.WALKING, speed = 1.8f)
        }
    }

    /** A dense near-home walk: a fix every 12 s carrying real Doppler speed but drifting only ~25 m,
     *  so it never leaves the stationary radius — only the speed signal can carve it out of a stay. */
    private fun tightWalk(fromMin: Long, toMin: Long, lat: Double = 40.0) {
        var t = at(fromMin)
        val end = at(toMin)
        var k = 0
        while (t <= end) {
            sample(t, lat + k * 0.000004, state = DevicePhysicalState.WALKING, speed = 1.0f)
            t += 12_000L
            k++
        }
    }

    /** The default scenario: a stay at 40.0 (10:00-10:30), a walk, a stay at 40.020 (10:50-11:20). */
    private fun seedTwoStaysWithWalkBetween() {
        stay(600, 630, lat = 40.0)
        walk(631, 649, fromLat = 40.001)
        stay(650, 680, lat = 40.020)
    }

    private fun confirmedVisit(
        id: Long,
        startMin: Long,
        endMin: Long,
        lat: Double = 40.0,
        placeId: Long? = null,
        ongoing: Boolean = false,
    ) = VisitEntity(
        id = id, placeId = placeId, candidateName = null, candidateGooglePlaceId = null,
        candidateLatitude = null, candidateLongitude = null, startMs = at(startMin),
        endMs = at(endMin), dayEpoch = day, centroidLatitude = lat, centroidLongitude = -74.0,
        radiusMeters = 30.0, confirmed = true, confidence = 1f, isOngoing = ongoing,
    )

    private fun confirmedTrip(
        id: Long,
        startMin: Long,
        endMin: Long,
        mode: TransportMode = TransportMode.CAR,
        fromVisitId: Long? = null,
        toVisitId: Long? = null,
    ) = TripEntity(
        id = id, fromVisitId = fromVisitId, toVisitId = toVisitId, startMs = at(startMin),
        endMs = at(endMin), dayEpoch = day, mode = mode, modeConfidence = 1f,
        encodedPolyline = Geo.encodePolyline(listOf(40.0 to -74.0, 40.005 to -74.0)),
        distanceMeters = 550.0, confirmed = true,
    )

    // ---- the basic pipeline -----------------------------------------------------------------

    @Test
    fun rebuild_materializesStaysAndFillsTheGapWithATrip() {
        seedTwoStaysWithWalkBetween()
        val inserted = rebuild()

        assertEquals(2, inserted)
        val visits = visitDao.visits.sortedBy { it.startMs }
        assertEquals(2, visits.size)
        assertTrue(visits.all { !it.confirmed })
        val (a, b) = visits
        assertEquals(at(600) to at(630), a.startMs to a.endMs)
        assertEquals(at(650) to at(680), b.startMs to b.endMs)
        assertEquals(40.0, a.centroidLatitude, 1e-6)
        assertEquals(40.020, b.centroidLatitude, 1e-6)
        // The latest fix is stationary inside B's span, so B is the ongoing stay.
        assertFalse(a.isOngoing)
        assertTrue(b.isOngoing)

        val trip = tripDao.trips.single()
        assertEquals(a.id, trip.fromVisitId)
        assertEquals(b.id, trip.toVisitId)
        assertEquals(TransportMode.WALKING, trip.mode)
        assertFalse(trip.confirmed)
        assertTrue(trip.distanceMeters > 1_000)
    }

    @Test
    fun inHomeGapBetweenConfirmedVisits_isNotFilledWithAPhantomTrip() {
        // The recorder split one home stay into confirmed-home -> [gap] -> confirmed-home (AR flipped
        // WALKING indoors). The gap's fixes are still at home, so the detector re-clusters the whole
        // span as one stay; dropped for overlapping the confirmed visits, its span must still MASK the
        // gap so no phantom walk is fabricated across it (the 06-13 in-apartment "trip").
        visitDao.seed(confirmedVisit(id = 1, startMin = 600, endMin = 630, lat = 40.0))
        visitDao.seed(confirmedVisit(id = 2, startMin = 690, endMin = 720, lat = 40.0))
        stay(600, 720, lat = 40.0) // continuous in-home fixes spanning both visits and the gap
        rebuild()
        assertTrue("no movement may be fabricated across an in-home gap", tripDao.trips.none { !it.confirmed })
    }

    @Test
    fun realWalkGapBetweenConfirmedVisits_isStillFilled() {
        // Contrast: the gap is a real walk away from home — the detector sees no stay there, nothing
        // masks it, so the trip is still built. Guards the mask against over-suppressing real movement.
        visitDao.seed(confirmedVisit(id = 1, startMin = 600, endMin = 630, lat = 40.0))
        visitDao.seed(confirmedVisit(id = 2, startMin = 690, endMin = 720, lat = 40.05))
        stay(600, 630, lat = 40.0)
        walk(631, 689, fromLat = 40.001)
        stay(690, 720, lat = 40.05)
        rebuild()
        assertTrue("a real walk in the gap must still be recorded", tripDao.trips.any { !it.confirmed })
    }

    @Test
    fun rebuild_isIdempotent() {
        seedTwoStaysWithWalkBetween()
        rebuild()
        val visitSpans = visitDao.visits.map { it.startMs to it.endMs }.sortedBy { it.first }
        val tripSpans = tripDao.trips.map { it.startMs to it.endMs }.sortedBy { it.first }

        rebuild()
        assertEquals(visitSpans, visitDao.visits.map { it.startMs to it.endMs }.sortedBy { it.first })
        assertEquals(tripSpans, tripDao.trips.map { it.startMs to it.endMs }.sortedBy { it.first })
    }

    @Test
    fun emptyDay_isANoOp() {
        assertEquals(0, rebuild())
        assertTrue(visitDao.visits.isEmpty())
        assertTrue(tripDao.trips.isEmpty())
    }

    // ---- overnight stays ---------------------------------------------------------------------

    @Test
    fun overnightStay_isOneSpanningVisit_acrossBothDaysRebuilds() {
        // 22:00 -> 04:00 next day, one fix every 10 minutes at the same spot.
        stay(22 * 60, 28 * 60, lat = 40.0, stepMin = 10)
        nowMs = at(29 * 60)

        rebuild(day)
        val afterDay1 = visitDao.visits.single()
        assertEquals(at(22 * 60) to at(28 * 60), afterDay1.startMs to afterDay1.endMs)

        rebuild(day + 1)
        // The day-2 rebuild deletes and re-detects the spanning row; it must come back as ONE
        // visit with the same full extent — never split at midnight.
        val afterDay2 = visitDao.visits.single()
        assertEquals(at(22 * 60) to at(28 * 60), afterDay2.startMs to afterDay2.endMs)

        // And re-running day 1 again still converges to the same single row.
        rebuild(day)
        assertEquals(1, visitDao.visits.size)
    }

    @Test
    fun multiDayUnconfirmedStay_survivesRebuildOfItsLastDay() {
        // A 46h continuous stay (day-2 12:00 -> day 10:00, a fix every 30 min) whose spanning row a
        // previous rebuild produced. Rebuilding its LAST day deletes the row (it overlaps the day),
        // so the sample window must widen to the row's full extent — with only the fixed 18h
        // lookback the stay would be re-detected from the lookback boundary, eroding ~a day.
        stay(-36 * 60, 10 * 60, lat = 40.0, stepMin = 30)
        nowMs = at(12 * 60)
        visitDao.seed(
            confirmedVisit(id = 50, startMin = -36 * 60, endMin = 10 * 60)
                .copy(confirmed = false, confidence = 0.5f),
        )

        rebuild(day)

        val v = visitDao.visits.single()
        assertFalse(v.confirmed)
        assertEquals(at(-36 * 60) to at(10 * 60), v.startMs to v.endMs)
    }

    // ---- evidence gaps ----------------------------------------------------------------------

    @Test
    fun recordingGap_splitsStayIntoTwoVisits() {
        // Same spot at 10:00-10:30 and 18:00-18:30 with nothing recorded between: presence across
        // the 7.5h gap must not be assumed, so two visits — not one fabricated 8h stay.
        stay(600, 630, lat = 40.0)
        stay(1080, 1110, lat = 40.0)
        nowMs = at(1140)

        rebuild()

        val visits = visitDao.visits.sortedBy { it.startMs }
        assertEquals(2, visits.size)
        assertEquals(at(600) to at(630), visits[0].startMs to visits[0].endMs)
        assertEquals(at(1080) to at(1110), visits[1].startMs to visits[1].endMs)
    }

    @Test
    fun staleLatestSample_doesNotExtendOngoingCandidateToNow() {
        // Too short to be a detected stay, so only the ongoing-candidate path materializes it. The
        // latest sample is 70 min old — beyond the evidence cap — so the stay closes at the last
        // fix instead of stretching to now.
        stay(628, 630, lat = 40.0)
        nowMs = at(700)

        rebuild()

        assertEquals(at(630), visitDao.visits.single().endMs)
    }

    @Test
    fun staleConfirmedOngoingVisit_closesAtLastEvidence() {
        // The ongoing confirmed stay has fresh samples up to 10:30, then recording stopped; at
        // 13:20 maintenance must close it at the last fix, not fabricate presence to now.
        stay(540, 630, lat = 40.0)
        visitDao.seed(
            confirmedVisit(id = 100, startMin = 540, endMin = 600, placeId = 7, ongoing = true),
        )
        nowMs = at(800)

        rebuild()

        val current = runBlocking { visitDao.byId(100) }!!
        assertEquals(at(630), current.endMs)
        assertFalse(current.isOngoing)
    }

    // ---- confirmed rows are ground truth ---------------------------------------------------------

    @Test
    fun confirmedVisit_suppressesRedetectionOverItsSpan() {
        seedTwoStaysWithWalkBetween()
        visitDao.seed(confirmedVisit(id = 100, startMin = 600, endMin = 630, placeId = 5))

        rebuild()

        // The first stay is already covered by ground truth: no unconfirmed twin appears on top.
        val visits = visitDao.visits.sortedBy { it.startMs }
        assertEquals(2, visits.size)
        assertEquals(100L, visits[0].id)
        assertTrue(visits[0].confirmed)
        assertFalse(visits[1].confirmed)
        // The gap is still filled, anchored on the confirmed visit's id.
        assertEquals(100L, tripDao.trips.single().fromVisitId)
    }

    @Test
    fun confirmedTrip_suppresssesStayRedetection_overItsSpan() {
        // The "converted stay resurrects" bug: the user turned the 10:00-10:30 stay into a moving
        // segment; the raw fixes still look stationary, but the confirmed trip must suppress
        // re-detection there.
        seedTwoStaysWithWalkBetween()
        tripDao.seed(confirmedTrip(id = 100, startMin = 598, endMin = 632))

        rebuild()

        assertTrue(visitDao.visits.none { it.startMs < at(632) })
        // The second stay still materializes normally.
        assertEquals(at(650), visitDao.visits.single().startMs)
        assertTrue(tripDao.trips.any { it.id == 100L && it.confirmed })
    }

    @Test
    fun confirmedTripStub_doesNotSwallowTheRestOfTheGap() {
        // The "vanishing trips" bug: a confirmed stub inside an inter-stay gap used to suppress
        // rebuilding the rest of the journey. The uncovered sub-intervals must still be filled.
        seedTwoStaysWithWalkBetween()
        tripDao.seed(confirmedTrip(id = 100, startMin = 636, endMin = 644))

        rebuild()

        val trips = tripDao.trips.sortedBy { it.startMs }
        assertEquals(3, trips.size)
        val (before, stub, after) = trips
        assertFalse(before.confirmed)
        assertTrue(before.endMs <= stub.startMs)
        assertEquals(100L, stub.id)
        assertFalse(after.confirmed)
        assertTrue(after.startMs >= stub.endMs)
    }

    // ---- ongoing visit & trip ---------------------------------------------------------------------

    @Test
    fun confirmedOngoingVisit_keepsTrackingNow_andStaleFlagsAreFinalized() {
        // The current stay (confirmed at 09:00, still there) plus a stale ongoing flag from
        // yesterday's confirm-while-ongoing.
        stay(540, 630, lat = 40.0)
        visitDao.seed(
            confirmedVisit(id = 100, startMin = 540, endMin = 600, placeId = 7, ongoing = true),
            confirmedVisit(id = 101, startMin = 480, endMin = 510, lat = 41.0, ongoing = true),
        )
        nowMs = at(660)

        rebuild()

        val current = runBlocking { visitDao.byId(100) }!!
        // The clock keeps tracking the present while the latest fix is stationary nearby...
        assertEquals(at(660), current.endMs)
        assertTrue(current.isOngoing)
        assertTrue(current.confirmed)
        // ...and only ONE visit may be ongoing: the stale flag is finalized.
        assertFalse(runBlocking { visitDao.byId(101) }!!.isOngoing)
    }

    @Test
    fun confirmedOngoingVisit_finalizesAtAWalk_insteadOfWeldingAcrossIt() {
        // The June 14 regression: an ongoing confirmed home stay, then a tight near-home walk, then
        // back home. The geometry path read the walk as continued presence and welded the stay across
        // it to `now`. With the speed-aware guard the stay must finalize at the walk and the walk must
        // surface as its own trip. (Contrast confirmedOngoingVisit_keepsTrackingNow, where the tail is
        // genuinely still — no run — and the clock still tracks now.)
        stay(540, 600, lat = 40.0)
        tightWalk(601, 612, lat = 40.0)
        stay(615, 650, lat = 40.0)
        visitDao.seed(confirmedVisit(id = 100, startMin = 540, endMin = 600, placeId = 7, ongoing = true))
        nowMs = at(655)

        rebuild()

        val current = runBlocking { visitDao.byId(100) }!!
        assertFalse("the stay must not weld across the walk", current.isOngoing)
        assertTrue("the stay closes around the walk, far short of now", current.endMs < at(615))
        assertTrue("the carved walk surfaces as a trip", tripDao.trips.any { !it.confirmed })
    }

    @Test
    fun movingTail_materializesAnOngoingTripWithNoDestination() {
        stay(600, 630, lat = 40.0)
        walk(631, 660, fromLat = 40.001)   // still moving; latest fix is WALKING
        nowMs = at(690)

        rebuild()

        val origin = visitDao.visits.single()
        val trip = tripDao.trips.single()
        assertEquals(origin.id, trip.fromVisitId)
        assertNull(trip.toVisitId)
        assertEquals(TransportMode.WALKING, trip.mode)
    }

    @Test
    fun rebuildingAPastDay_whileCurrentlyMoving_buildsNoOngoingTrip() {
        // Yesterday had a normal stay; right now the user is walking (the latest sample is in
        // TODAY). Rebuilding the PAST day must not segment the span from yesterday's last visit to
        // now into a bogus ongoing trip — the latest sample doesn't belong to the maintained day.
        stay(600, 630, lat = 40.0)
        walk(24 * 60 + 600, 24 * 60 + 630, fromLat = 40.001)
        nowMs = at(24 * 60 + 631)

        rebuild(day)

        assertEquals(at(600) to at(630), visitDao.visits.single().let { it.startMs to it.endMs })
        assertTrue(tripDao.trips.isEmpty())
    }

    // ---- integrity sweep ---------------------------------------------------------------------------

    @Test
    fun danglingConfirmedTripEndpoints_areDetached() {
        stay(600, 630, lat = 40.0)
        tripDao.seed(
            confirmedTrip(id = 100, startMin = 700, endMin = 710, fromVisitId = 9998, toVisitId = 9999),
        )

        rebuild()

        val trip = runBlocking { tripDao.byId(100) }!!
        assertNull(trip.fromVisitId)
        assertNull(trip.toVisitId)
    }

    // ---- ongoing-stay coarse sustain -----------------------------------------------------------------

    @Test
    fun ongoingStay_closesAtLastCoarseSustainingFix_whenEvidenceIsStale() {
        // One precise stationary fix (too few for the detector to emit a cluster), then a Doze-style
        // run of coarse (100 m) stationary fixes at the same spot, then nothing. `now` is an hour
        // past the last fix, so the stay must CLOSE — at the last coarse sustaining fix, not be
        // truncated back to the lone precise one.
        sample(at(600), 40.0, state = DevicePhysicalState.STATIONARY, speed = 0f)
        for (m in 605L..660L step 5) {
            sample(at(m), 40.0, state = DevicePhysicalState.STATIONARY, speed = 0f, accuracy = 100f)
        }
        nowMs = at(720)
        rebuild()
        val v = visitDao.visits.single()
        assertEquals(at(600), v.startMs)
        assertEquals(at(660), v.endMs)
    }

    // ---- subtractRanges ------------------------------------------------------------------------------

    @Test
    fun subtractRanges_table() {
        assertEquals(listOf(0L to 100L), subtractRanges(0, 100, emptyList()))
        assertEquals(
            listOf(0L to 40L, 60L to 100L),
            subtractRanges(0, 100, listOf(40L to 60L)),
        )
        assertEquals(listOf(20L to 100L), subtractRanges(0, 100, listOf(-10L to 20L)))
        assertEquals(listOf(0L to 80L), subtractRanges(0, 100, listOf(80L to 120L)))
        assertTrue(subtractRanges(0, 100, listOf(0L to 100L)).isEmpty())
        assertEquals(
            listOf(10L to 20L, 40L to 50L),
            subtractRanges(0, 50, listOf(0L to 10L, 20L to 40L)),
        )
        // Out-of-order, overlapping blocks still subtract correctly.
        assertEquals(
            listOf(0L to 10L, 70L to 100L),
            subtractRanges(0, 100, listOf(30L to 70L, 10L to 40L)),
        )
    }
}
