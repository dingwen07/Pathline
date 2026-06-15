package net.extrawdw.apps.locationhistory.service

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.RecorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table tests for [RecordingHeuristics] — the pure decision core extracted from
 * [RecordingController] (backlog #7). Pins the June 2026 movement-undersampling rules:
 * [RecordingHeuristics.recentlyMoving] (premature AR-STILL rejection), the noise-widened drift
 * guard, the stationary-cluster detector, and the significant-motion backoff curve.
 */
class RecordingHeuristicsTest {

    private val t0 = 1_750_000_000_000L
    private val h = RecordingHeuristics()

    /** ~1.1 m per 1e-5 deg of latitude. */
    private fun fix(
        atSec: Long,
        lat: Double = 40.0,
        lon: Double = -74.0,
        accuracy: Float? = 10f,
        speed: Float? = 0f,
        stationary: Boolean = true,
    ) = RecentFix(t0 + atSec * 1000, lat, lon, accuracy, speed, stationary)

    // ---- recentlyMoving -------------------------------------------------------------------------

    @Test
    fun recentlyMoving_falseWhenSettled() {
        repeat(5) { h.pushFix(fix(atSec = it * 10L, speed = 0.2f)) }
        assertFalse(h.recentlyMoving())
    }

    @Test
    fun recentlyMoving_falseOnGpsSpeedWithoutDisplacement() {
        // The 06-13 phantom: GPS reports speed while the phone sits still (indoor multipath Doppler).
        // Speed alone is no longer trusted -- without net displacement this reads as NOT moving, so a
        // correct AR STILL is accepted instead of being rejected for hours.
        h.pushFix(fix(atSec = 0, speed = 0.5f))
        h.pushFix(fix(atSec = 10, speed = 5f))
        h.pushFix(fix(atSec = 20, speed = 3f))
        assertFalse(h.recentlyMoving())
    }

    @Test
    fun recentlyMoving_falseOnPhantomScatterAroundFixedCentroid() {
        // Wave C: position oscillates ~130 m around a FIXED home centroid (multipath) with high
        // reported speed. Raw spread would read as movement; the half-vs-half net displacement cancels
        // the zero-mean scatter, so this stays NOT moving.
        for (i in 0 until 12) {
            val jitter = if (i % 2 == 0) 0.0 else 0.0012  // ~133 m swing, oscillating in place
            h.pushFix(fix(atSec = i * 7L, lat = 40.0 + jitter, speed = 8f))
        }
        assertFalse(h.recentlyMoving())
    }

    @Test
    fun recentlyMoving_trueOnProgressingTranslation() {
        // A real trip: the centroid actually advances across the window (no GPS speed needed). Moving
        // fixes are classified non-stationary, so they don't inflate the noise radius.
        for (i in 0 until 12) {
            h.pushFix(fix(atSec = i * 7L, lat = 40.0 + i * 0.0002, speed = null, stationary = false))
        }
        assertTrue(h.recentlyMoving())
    }

    @Test
    fun recentlyMoving_trueOnSpreadBeyondStayRadius_evenWithoutSpeed() {
        // ~110 m apart > 60 m stay radius; speeds null (e.g. network fixes).
        h.pushFix(fix(atSec = 0, lat = 40.0, speed = null))
        h.pushFix(fix(atSec = 30, lat = 40.001, speed = null))
        assertTrue(h.recentlyMoving())
    }

    @Test
    fun recentlyMoving_ignoresMovementOlderThanTheWindow() {
        // A real displacement minutes ago must not block a genuine stop now: only the last 90 s count.
        // The far fix ages out of the motion window, leaving a settled cluster -> not moving.
        h.pushFix(fix(atSec = 0, lat = 40.005, speed = null))  // ~550 m away, long ago
        repeat(6) { h.pushFix(fix(atSec = 300 + it * 10L, lat = 40.0, speed = null)) }
        assertFalse(h.recentlyMoving())
    }

    @Test
    fun recentlyMoving_needsAtLeastTwoRecentFixes() {
        h.pushFix(fix(atSec = 0, speed = 20f))
        assertFalse(h.recentlyMoving().also { /* single fix - no verdict */ })
    }

    @Test
    fun recentlyMoving_ignoresSpreadFromCoarseFixes() {
        // Indoor reality: 150 m-accuracy fixes scatter ~150 m apart while the phone sits still.
        // Position spread from untrustworthy fixes must not read as movement — this is what kept
        // the recorder out of the stationary cadence for whole idle days (June 2026).
        h.pushFix(fix(atSec = 0, lat = 40.0, accuracy = 150f, speed = null))
        h.pushFix(fix(atSec = 30, lat = 40.0014, accuracy = 150f, speed = null))
        h.pushFix(fix(atSec = 60, lat = 40.0, accuracy = 150f, speed = 0f))
        assertFalse(h.recentlyMoving())
    }

    @Test
    fun recentlyMoving_spreadComparedToNoiseWidenedRadius() {
        // ~77 m apart with good accuracy: beyond the 60 m stay radius but inside the 80 m
        // drift-displacement floor the spread check now uses — still settled.
        h.pushFix(fix(atSec = 0, lat = 40.0, speed = null))
        h.pushFix(fix(atSec = 30, lat = 40.0007, speed = null))
        assertFalse(h.recentlyMoving())
    }

    // ---- stationary cluster detector --------------------------------------------------------------

    private fun settle(seconds: LongRange, stepSec: Long = 30) {
        for (sec in seconds step stepSec) h.pushFix(fix(atSec = sec, speed = 0.1f))
    }

    @Test
    fun cluster_emergesAfterSettlingForMinVisitDuration() {
        settle(0L..240L)    // 4 min of tight, accurate, slow fixes (MIN_VISIT is 3 min)
        val c = h.stationaryClusterCandidate()
        assertNotNull(c)
        assertEquals(40.0, c!!.centroidLatitude, 1e-6)
        assertTrue(c.endMs - c.startMs >= (Constants.MIN_VISIT_DURATION_MS * 0.8).toLong())
    }

    @Test
    fun cluster_nullWhileWindowTooShort() {
        settle(0L..60L)
        assertNull(h.stationaryClusterCandidate())
    }

    @Test
    fun cluster_nullOnPoorAccuracy() {
        for (sec in 0L..240L step 30) h.pushFix(fix(atSec = sec, accuracy = 90f, speed = 0.1f))
        assertNull(h.stationaryClusterCandidate())
    }

    @Test
    fun cluster_nullWhenStillDrifting() {
        // Mean speed above the 0.6 m/s gate.
        for (sec in 0L..240L step 30) h.pushFix(fix(atSec = sec, speed = 1.0f))
        assertNull(h.stationaryClusterCandidate())
    }

    @Test
    fun cluster_nullWhenSpreadExceedsStayRadius() {
        // Fixes alternate between two points ~220 m apart.
        for ((i, sec) in (0L..240L step 30).withIndex()) {
            h.pushFix(fix(atSec = sec, lat = if (i % 2 == 0) 40.0 else 40.002, speed = 0.1f))
        }
        assertNull(h.stationaryClusterCandidate())
    }

    @Test
    fun cluster_nullWhenFixesNotMostlyStationary() {
        for ((i, sec) in (0L..240L step 30).withIndex()) {
            h.pushFix(fix(atSec = sec, speed = 0.1f, stationary = i % 2 == 0))
        }
        assertNull(h.stationaryClusterCandidate())
    }

    @Test
    fun cluster_emergesDespiteCoarseOutliers() {
        // The indoor cold-start case: good ~50 m-accuracy fixes settled at home, interleaved with
        // 200 m-accuracy fixes scattering ~300 m away. The coarse outliers must neither veto the
        // cluster (old all-fixes radius check) nor starve it (old 70%-of-all-fixes accuracy gate).
        for ((i, sec) in (0L..240L step 30).withIndex()) {
            if (i % 3 == 2) {
                h.pushFix(fix(atSec = sec, lat = 40.003, accuracy = 200f, speed = null))
            } else {
                h.pushFix(fix(atSec = sec, lat = 40.0, accuracy = 50f, speed = 0.1f))
            }
        }
        val c = h.stationaryClusterCandidate()
        assertNotNull(c)
        // Centroid anchored by the trusted fixes, not dragged toward the outliers.
        assertEquals(40.0, c!!.centroidLatitude, 1e-4)
    }

    @Test
    fun cluster_nullWhenTrustedFixesClumpAtOneEnd() {
        // Coarse fixes for most of the window, 3 good fixes only in the last 45 s: the trusted
        // subset can't anchor a 3-minute stationarity verdict on its own.
        for (sec in 0L..180L step 30) {
            h.pushFix(fix(atSec = sec, accuracy = 200f, speed = null))
        }
        for (sec in 195L..240L step 15) {
            h.pushFix(fix(atSec = sec, accuracy = 10f, speed = 0.1f))
        }
        assertNull(h.stationaryClusterCandidate())
    }

    // ---- drift guard -----------------------------------------------------------------------------

    @Test
    fun drift_neverWhileMoving() {
        h.stationaryAnchor = 40.0 to -74.0
        assertFalse(h.isDriftAt(RecorderState.MOVING, 40.0, -74.0, 0f, 0f))
    }

    @Test
    fun drift_neverWithoutAnchor() {
        // Stationary but anchorless: nothing to measure displacement against, so it can't be
        // called drift — assuming drift here would suppress every departure forever.
        assertFalse(h.isDriftAt(RecorderState.STATIONARY, 40.0, -74.0, 0f, 0f))
    }

    @Test
    fun drift_neverWithoutAnchor_evenAtGpsSpeed() {
        // Anchorless: nothing to measure displacement against, so even a speed reading can't be called
        // drift -- a departure is never suppressed for lack of an anchor.
        assertFalse(
            h.isDriftAt(
                RecorderState.STATIONARY, 40.0, -74.0,
                Constants.DRIFT_MOVING_SPEED_MPS, 0f,
            ),
        )
    }

    @Test
    fun drift_overriddenByPhysicalMotion_notByBareGpsSpeed() {
        h.stationaryAnchor = 40.0 to -74.0
        // At the anchor with bare GPS speed but NO net displacement: phantom Doppler -> STILL drift
        // (the 06-13 phantom-speed false departures). Speed alone no longer ejects the stay.
        assertTrue(
            h.isDriftAt(
                RecorderState.STATIONARY, 40.0, -74.0,
                Constants.DRIFT_MOVING_SPEED_MPS, 0f,
            ),
        )
        // At the anchor, no GPS speed, but the phone is shaking (a real walk) -> not drift.
        assertFalse(
            h.isDriftAt(
                RecorderState.STATIONARY, 40.0, -74.0,
                0f, Constants.DRIFT_MOTION_VARIANCE_CEILING,
            ),
        )
    }

    @Test
    fun drift_nearAnchorPhysicallyStill_isDrift_farIsNot() {
        h.stationaryAnchor = 40.0 to -74.0
        // ~33 m from the anchor, still, slow: jitter.
        assertTrue(h.isDriftAt(RecorderState.STATIONARY, 40.0003, -74.0, 0f, 0f))
        // ~220 m away: beyond any noise widening (cap is 150 m): a real departure.
        assertFalse(h.isDriftAt(RecorderState.STATIONARY, 40.002, -74.0, 0f, 0f))
    }

    @Test
    fun noiseRadius_floorsAtDriftDisplacement_widensWithGpsNoise_capsAtPlaceMax() {
        // Fewer than 3 stationary fixes: the floor.
        assertEquals(Constants.DRIFT_DISPLACEMENT_METERS, h.stationaryNoiseRadius(), 1e-9)
        // A noisy place: stationary fixes wobbling ~110 m around the centroid widen the radius…
        for ((i, sec) in (0L..240L step 30).withIndex()) {
            h.pushFix(fix(atSec = sec, lat = if (i % 2 == 0) 40.0 else 40.002, speed = 0f))
        }
        // …but never past the place-radius cap.
        assertEquals(Constants.PLACE_MAX_RADIUS_METERS, h.stationaryNoiseRadius(), 1e-9)
    }

    @Test
    fun noiseRadius_ignoresNonStationaryFixes() {
        // A departure trajectory (stationary=false) must not inflate the dead zone.
        for (sec in 0L..240L step 30) {
            h.pushFix(fix(atSec = sec, lat = 40.0 + sec * 1e-5, speed = 2f, stationary = false))
        }
        assertEquals(Constants.DRIFT_DISPLACEMENT_METERS, h.stationaryNoiseRadius(), 1e-9)
    }

    // ---- speed stats & buffers ---------------------------------------------------------------------

    @Test
    fun speedStats_meanMaxVariance_overCappedWindow() {
        assertEquals(Triple(0f, 0f, 0f), h.speedStats(t0))
        // 12 pushes within the time window; the count cap keeps the last 10 (first two evicted).
        h.pushSpeed(100f, t0)
        h.pushSpeed(50f, t0 + 1000)
        repeat(10) { h.pushSpeed(2f, t0 + 2000 + it * 1000L) }
        val (mean, max, variance) = h.speedStats(t0 + 12_000)
        assertEquals(2f, mean)
        assertEquals(2f, max)
        assertEquals(0f, variance)
    }

    @Test
    fun speedStats_drainsWhenSpeedStops() {
        // The 06-13 4h drain: one speed spike, then the Doppler stream stalls (speed-less network
        // fixes). The window must drain by time so the spike stops counting -- speedStats is queried
        // with each fix's time even when no new speed arrives, draining it past SPEED_WINDOW_MS.
        h.pushSpeed(8f, t0)
        assertEquals(Triple(0f, 0f, 0f), h.speedStats(t0 + 120_000))
    }

    @Test
    fun fixBuffer_trimsToRollingWindow() {
        h.pushFix(fix(atSec = 0, speed = 30f))
        // 7 minutes later (beyond the 6-minute fix window) the old fast fix is gone.
        h.pushFix(fix(atSec = 420, speed = 0f))
        h.pushFix(fix(atSec = 430, speed = 0f))
        assertFalse(h.recentlyMoving())
    }

    // ---- significant-motion backoff ------------------------------------------------------------------

    @Test
    fun sigMotionBackoff_doublesAndCaps() {
        assertEquals(30_000L, RecordingHeuristics.sigMotionBackoffMs(0))
        assertEquals(60_000L, RecordingHeuristics.sigMotionBackoffMs(1))
        assertEquals(120_000L, RecordingHeuristics.sigMotionBackoffMs(2))
        assertEquals(16 * 60_000L, RecordingHeuristics.sigMotionBackoffMs(5))
        assertEquals(30 * 60_000L, RecordingHeuristics.sigMotionBackoffMs(6))
        // The streak keeps growing past the shift cap; the delay must not overflow or grow.
        assertEquals(30 * 60_000L, RecordingHeuristics.sigMotionBackoffMs(40))
    }

    // ---- sustainedDopplerMoving (MOVING keep-alive) ---------------------------------------------

    @Test
    fun sustainedDoppler_falseAtPinnedCentroidDespiteSpikes() {
        // Phantom Doppler at a FIXED point: the spikes pass the speed fraction, but net displacement is
        // ~0 so the keep-alive must NOT fire (the 06-13 drain guard — this is the sole barrier once the
        // cluster detector bails on meanSpeed>0.6).
        for (sec in 0L..180L step 10) {
            val spd = if (sec % 30 == 0L) 3f else 0.2f
            h.pushFix(fix(atSec = sec, lat = 40.0, lon = -74.0, accuracy = 20f, speed = spd, stationary = false))
        }
        assertFalse(h.sustainedDopplerMoving())
    }

    @Test
    fun sustainedDoppler_trueOnProgressingSlowTrip() {
        // ~1 m/s progressing trip with accurate fixes carrying valid Doppler: holds MOVING.
        for (sec in 0L..180L step 10) {
            h.pushFix(fix(atSec = sec, lat = 40.0 + sec * 0.0000090, accuracy = 20f, speed = 1.0f, stationary = false))
        }
        assertTrue(h.sustainedDopplerMoving())
    }

    @Test
    fun sustainedDoppler_falseWhenProgressingButNoValidSpeed() {
        // Real translation but speed below the walking floor: the keep-alive needs BOTH -> false.
        for (sec in 0L..180L step 10) {
            h.pushFix(fix(atSec = sec, lat = 40.0 + sec * 0.0000090, accuracy = 20f, speed = 0.3f, stationary = false))
        }
        assertFalse(h.sustainedDopplerMoving())
    }

    @Test
    fun sustainedDoppler_falseWhenDopplerOnlyOnCoarseFixes() {
        // Valid speed but only on coarse (>30 m) fixes: excluded by the Doppler accuracy gate -> false.
        for (sec in 0L..180L step 10) {
            h.pushFix(fix(atSec = sec, lat = 40.0 + sec * 0.0000090, accuracy = 45f, speed = 1.0f, stationary = false))
        }
        assertFalse(h.sustainedDopplerMoving())
    }

    // ---- hasLeftStayBeyondAccuracy (SENSING escalation gate) ------------------------------------

    @Test
    fun hasLeftStayBeyondAccuracy_requiresDisplacementBeyondBothRadiusAndAccuracy() {
        h.stationaryAnchor = 40.0 to -74.0
        // Near + accurate: within the stay.
        assertFalse(h.hasLeftStayBeyondAccuracy(40.0001, -74.0, 10f))   // ~11 m < 80 m radius
        // Far + accurate: a real, large move.
        assertTrue(h.hasLeftStayBeyondAccuracy(40.001, -74.0, 10f))     // ~111 m >= 80 m
        // Displaced past the radius but NOT past its own (coarse) accuracy: not trusted to attest.
        assertFalse(h.hasLeftStayBeyondAccuracy(40.0008, -74.0, 100f))  // ~89 m < max(80, 100)
        // A coarse fix CAN attest to a large enough move (beyond its accuracy).
        assertTrue(h.hasLeftStayBeyondAccuracy(40.02, -74.0, 100f))     // ~2220 m >= max(80, 100)
        // No anchor: nothing to be displaced from.
        h.stationaryAnchor = null
        assertFalse(h.hasLeftStayBeyondAccuracy(40.02, -74.0, 10f))
    }
}
