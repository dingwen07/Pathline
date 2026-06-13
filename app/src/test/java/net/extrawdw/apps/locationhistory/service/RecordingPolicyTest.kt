package net.extrawdw.apps.locationhistory.service

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.RecorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table tests for [RecordingPolicy] — the pure recorder state machine (refactor plan Phase 2).
 *
 * Each scenario is drawn from a real 2026-06-13 device log, encoded so a regression reproduces as a
 * failing row instead of a multi-hour battery dump:
 *  - [clusterEntersStationary] — user start, clean GPS settles into a stay (075952).
 *  - [unknownIdleTimeoutEntersStationary] — boot/restart while parked, GPS too noisy to cluster, so
 *    the recorder self-demotes instead of holding UNKNOWN for hours (025057, 032350).
 *  - [resumeRepairEntersStationary] — START_STICKY restart with stored stationary fixes (093056).
 *  - [dozeIdleEntersStationary] — parked through repeated Doze entries (032350).
 *  - [departureVerifyConfirmed]/[departureVerifyTimesOut] — the verify-before-committing path.
 */
class RecordingPolicyTest {

    private val t0 = 1_750_000_000_000L
    private val policy = RecordingPolicy(RecordingHeuristics())

    private fun fix(
        atSec: Long,
        lat: Double = 40.0,
        lon: Double = -74.0,
        accuracy: Float? = 10f,
        speed: Float? = 0f,
        state: DevicePhysicalState = DevicePhysicalState.STATIONARY,
        confident: Boolean = true,
    ) = ClassifiedFix(
        fix = RecentFix(t0 + atSec * 1000, lat, lon, accuracy, speed, state == DevicePhysicalState.STATIONARY),
        classifiedState = state,
        isConfident = confident,
    )

    private fun feed(atSec: Long, motionVariance: Float = 0f, fix: ClassifiedFix) =
        policy.onFixes(listOf(fix), motionVariance, t0 + atSec * 1000)

    // ---- stationary entry paths -----------------------------------------------------------------

    @Test
    fun clusterEntersStationary() {
        var sawCluster = false
        for (sec in 0L..240L step 30) {
            val actions = feed(sec, fix = fix(sec, speed = 0.1f))
            if (actions.any { it is RecordingAction.EnterStationary && it.reason == "fix_cluster" }) sawCluster = true
        }
        assertTrue("a clean settle should enter STATIONARY via fix_cluster", sawCluster)
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun unknownIdleTimeoutEntersStationary() {
        // Coarse (>gate) fixes never form a cluster, but a quiet IMU + no AR motion + elapsed time
        // means the device is simply parked: it must drop to low power instead of holding UNKNOWN.
        var demotedAtSec: Long? = null
        for (sec in 0L..270L step 30) {
            val actions = feed(sec, fix = fix(sec, accuracy = 120f))
            if (actions.any { it is RecordingAction.EnterStationary && it.reason == "unknown_idle_timeout" }) {
                demotedAtSec = sec
                break
            }
            // Before the timeout elapses it must stay UNKNOWN (don't demote prematurely).
            if (sec * 1000 < Constants.UNKNOWN_IDLE_TIMEOUT_MS) {
                assertEquals(RecorderState.UNKNOWN, policy.state)
            }
        }
        assertEquals(RecorderState.STATIONARY, policy.state)
        assertTrue("should demote at/after the idle timeout", (demotedAtSec ?: 0L) * 1000 >= Constants.UNKNOWN_IDLE_TIMEOUT_MS)
    }

    @Test
    fun idleTimeoutSuppressedWhileImuActive() {
        // A shaking phone (high motion variance) is not "parked", so no self-demotion.
        for (sec in 0L..300L step 30) feed(sec, motionVariance = 5f, fix = fix(sec, accuracy = 120f))
        assertEquals(RecorderState.UNKNOWN, policy.state)
    }

    @Test
    fun resumeRepairEntersStationary() {
        val stored = (0L..240L step 30).map {
            RecentFix(t0 + it * 1000, 40.0, -74.0, 10f, 0f, stationary = true)
        }
        val actions = policy.onResumeRepair(stored, t0 + 240_000)
        assertTrue(actions.any { it is RecordingAction.EnterStationary && it.reason == "resume_repair" })
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun resumeRepairNoOpWhenLastStoredFixMoving() {
        val stored = (0L..210L step 30).map {
            RecentFix(t0 + it * 1000, 40.0, -74.0, 10f, 0f, stationary = true)
        } + RecentFix(t0 + 240_000, 40.01, -74.0, 10f, 5f, stationary = false)
        assertTrue(policy.onResumeRepair(stored, t0 + 240_000).isEmpty())
        assertEquals(RecorderState.UNKNOWN, policy.state)
    }

    @Test
    fun dozeIdleEntersStationary() {
        feed(0, fix = fix(0)) // buffer one fix to anchor the stay
        val actions = policy.onDozeIdle(idle = true, motionVariance = 0f, nowMs = t0 + 1_000)
        assertTrue(actions.any {
            it is RecordingAction.EnterStationary && it.reason == "doze_idle" && !it.armSigMotion
        })
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun dozeIdleNoOpWithoutAnchor() {
        val actions = policy.onDozeIdle(idle = true, motionVariance = 0f, nowMs = t0)
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.UNKNOWN, policy.state)
    }

    // ---- AR transitions -------------------------------------------------------------------------

    @Test
    fun arStillEntersStationaryWhenSettled() {
        feed(0, fix = fix(0)) // one buffered fix; recentlyMoving is false with <2 recent fixes
        val actions = policy.onArTransitions(listOf(ArActivity.STILL to true), t0 + 1_000)
        assertTrue(actions.any { it is RecordingAction.EnterStationary && it.reason == "ar_still" })
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun arStillIgnoredWhileRecentlyMoving() {
        feed(0, motionVariance = 1f, fix = fix(0, speed = 5f, state = DevicePhysicalState.WALKING))
        feed(10, motionVariance = 1f, fix = fix(10, speed = 5f, state = DevicePhysicalState.WALKING))
        assertEquals(RecorderState.MOVING, policy.state)
        val actions = policy.onArTransitions(listOf(ArActivity.STILL to true), t0 + 11_000)
        assertTrue("STILL must be ignored while fixes still show motion", actions.isEmpty())
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun arVehicleFromStationaryEntersMovingDirectly() {
        feed(0, fix = fix(0))
        policy.onArTransitions(listOf(ArActivity.STILL to true), t0 + 1_000)
        assertEquals(RecorderState.STATIONARY, policy.state)
        val actions = policy.onArTransitions(listOf(ArActivity.IN_VEHICLE to true), t0 + 2_000)
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "ar_vehicle" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun arWalkingFromStationaryVerifiesFirst() {
        feed(0, fix = fix(0))
        policy.onArTransitions(listOf(ArActivity.STILL to true), t0 + 1_000)
        val actions = policy.onArTransitions(listOf(ArActivity.WALKING to true), t0 + 2_000)
        assertTrue(actions.any { it is RecordingAction.BeginVerifying && it.reason == "ar_walking" })
        assertEquals(RecorderState.VERIFYING_DEPARTURE, policy.state)
    }

    // ---- departure verification -----------------------------------------------------------------

    @Test
    fun departureVerifyConfirmed() {
        enterStationary()
        assertTrue(policy.onSignificantMotion(t0 + 250_000).any { it is RecordingAction.BeginVerifying })
        assertEquals(RecorderState.VERIFYING_DEPARTURE, policy.state)
        val actions = feed(260, motionVariance = 2f, fix = fix(260, lat = 40.02, speed = 5f, state = DevicePhysicalState.WALKING))
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "verify_confirmed" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun verifyConfirmsOnDisplacementWithoutSpeed() {
        // A slow departure with no Doppler speed (e.g. network fixes) must still confirm once it has
        // displaced beyond the stay's noise radius — the bare classifier verdict would never fire.
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.VERIFYING_DEPARTURE, policy.state)
        // ~220 m north of the anchor, speed 0, classified STATIONARY, still IMU.
        val actions = feed(260, motionVariance = 0f, fix = fix(260, lat = 40.002, speed = 0f))
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "verify_confirmed" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun verifyDoesNotConfirmOnDriftFix() {
        // Phantom GPS drift at the anchor (near, no real speed, still) must NOT confirm a departure —
        // it is exactly the transient the verify window exists to suppress.
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.VERIFYING_DEPARTURE, policy.state)
        // ~11 m from the anchor, sub-threshold speed, still: within the stay.
        val actions = feed(260, motionVariance = 0f, fix = fix(260, lat = 40.0001, speed = 0.5f, state = DevicePhysicalState.WALKING))
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.VERIFYING_DEPARTURE, policy.state)
    }

    @Test
    fun fixDepartureFoundLaterInBatch() {
        // A batch whose FIRST moving fix is in-radius drift but a LATER fix is a real departure must
        // still leave the stay — the drift fix must not mask the departure.
        enterStationary()
        val drift = fix(250, lat = 40.0001, speed = 0f, state = DevicePhysicalState.WALKING, confident = true)
        val departure = fix(251, lat = 40.02, speed = 5f, state = DevicePhysicalState.WALKING, confident = true)
        val actions = policy.onFixes(listOf(drift, departure), motionVariance = 0f, nowMs = t0 + 251_000)
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "fix_departure" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun departureVerifyTimesOut() {
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.VERIFYING_DEPARTURE, policy.state)
        val actions = policy.onVerifyDeadline(t0 + 250_000 + Constants.DEPARTURE_VERIFY_WINDOW_MS)
        assertTrue(actions.any { it is RecordingAction.RevertToStationary })
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun significantMotionIgnoredWhenNotStationary() {
        assertTrue(policy.onSignificantMotion(t0).isEmpty()) // starts UNKNOWN
        assertEquals(RecorderState.UNKNOWN, policy.state)
    }

    @Test
    fun geofenceExitEntersMoving() {
        enterStationary()
        val actions = policy.onGeofenceExit(t0 + 250_000)
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "geofence_exit" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun fixDepartureSuppressedAsDriftWhileStationary() {
        enterStationary()
        // A near-anchor, physically-still, no-speed fix is drift, not a departure.
        val actions = feed(250, motionVariance = 0f, fix = fix(250, lat = 40.0001, speed = 0f, state = DevicePhysicalState.WALKING, confident = true))
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    /** Drive the policy into a clean STATIONARY stay anchored at (40.0, -74.0). */
    private fun enterStationary() {
        for (sec in 0L..240L step 30) feed(sec, fix = fix(sec, speed = 0.1f))
        assertEquals(RecorderState.STATIONARY, policy.state)
    }
}
