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

    private fun feed(atSec: Long, motionVariance: Float = 0f, stepDelta: Int = 0, fix: ClassifiedFix) =
        policy.onFixes(listOf(fix), motionVariance, t0 + atSec * 1000, stepDelta)

    /** A bare confirming fix for the geofence-exit check (no classification needed). */
    private fun freshFix(lat: Double = 40.0, lon: Double = -74.0, accuracy: Float? = 10f, speed: Float? = 0f) =
        RecentFix(t0 + 250_000, lat, lon, accuracy, speed, stationary = false)

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
    fun idleTimeoutFiresOnVibrationWithoutSteps() {
        // Review #2: vibration alone (a phone on an idling-car seat / a washing machine) is NOT travel.
        // High IMU energy with no steps and no net displacement must still self-demote, not pin the
        // cadence — raw motionVariance is no longer treated as "moving".
        var sawDemote = false
        for (sec in 0L..300L step 30) {
            val actions = feed(sec, motionVariance = 5f, fix = fix(sec, accuracy = 120f))
            if (actions.any { it is RecordingAction.EnterStationary }) sawDemote = true
        }
        assertTrue("vibration without steps/displacement must self-demote", sawDemote)
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun idleTimeoutSuppressedWhileStepping() {
        // Real walking (steps) holds off the self-demote even when GPS is too coarse to show net
        // displacement — steps are motion evidence that vibration is not.
        for (sec in 0L..300L step 30) feed(sec, motionVariance = 5f, stepDelta = 10, fix = fix(sec, accuracy = 120f))
        assertEquals(RecorderState.UNKNOWN, policy.state)
    }

    @Test
    fun arMovingOscillationDoesNotPinMoving() {
        // Review #1: AR oscillating between moving activities (WALKING<->VEHICLE) without ever emitting
        // STILL must NOT pin MOVING. AR-moving no longer resets the quiet clock, so a still phone still
        // self-demotes on the budget despite continuous AR jitter — the precise drain it exists to kill.
        policy.onArTransitions(listOf(ArActivity.IN_VEHICLE to true), t0)
        var demoted = false
        var sec = 10L
        while (sec * 1000 <= Constants.MOVING_IDLE_TIMEOUT_MS + 60_000) {
            if (sec % 60 == 10L) {  // a spurious AR-moving ENTER every minute, never a STILL
                val act = if ((sec / 60) % 2 == 0L) ArActivity.WALKING else ArActivity.IN_VEHICLE
                policy.onArTransitions(listOf(act to true), t0 + sec * 1000)
            }
            val actions = feed(sec, motionVariance = 0f, fix = fix(sec, accuracy = 120f, speed = null, state = DevicePhysicalState.STATIONARY))
            if (actions.any { it is RecordingAction.EnterStationary && it.reason == "moving_idle_timeout" }) demoted = true
            if (policy.state == RecorderState.STATIONARY) break
            sec += 20
        }
        assertTrue("AR-moving oscillation must not block the self-demote", demoted)
        assertEquals(RecorderState.STATIONARY, policy.state)
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
        // A smooth ride AR briefly mislabels STILL: the fixes are genuinely TRANSLATING, so STILL must
        // be rejected. Real net displacement -- not bare GPS speed -- is what blocks it now.
        for (i in 0 until 8) {
            feed(i * 5L, motionVariance = 5f, fix = fix(i * 5L, lat = 40.0 + i * 0.0005, speed = 5f, state = DevicePhysicalState.WALKING))
        }
        assertEquals(RecorderState.MOVING, policy.state)
        val actions = policy.onArTransitions(listOf(ArActivity.STILL to true), t0 + 40_000)
        assertTrue("STILL must be ignored while fixes still show real translation", actions.isEmpty())
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun arStillAcceptedDespitePhantomSpeed() {
        // The 06-13 wave C: AR reports STILL while GPS fakes speed on a motionless phone (centroid
        // fixed). With bare speed no longer trusted, the correct STILL is accepted -> low power.
        for (i in 0 until 8) {
            val jitter = if (i % 2 == 0) 0.0 else 0.0012  // oscillating in place, no net progress
            feed(i * 5L, motionVariance = 0f, fix = fix(i * 5L, lat = 40.0 + jitter, speed = 8f, state = DevicePhysicalState.WALKING))
        }
        val actions = policy.onArTransitions(listOf(ArActivity.STILL to true), t0 + 40_000)
        assertTrue(actions.any { it is RecordingAction.EnterStationary && it.reason == "ar_still" })
        assertEquals(RecorderState.STATIONARY, policy.state)
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
    fun verifyDoesNotConfirmOnPhantomSpeedAtAnchor() {
        // Phantom Doppler at the anchor (near, high reported speed, still, no net displacement) must
        // NOT confirm a departure -- bare GPS speed no longer ejects the stay (the 06-13 phantom exits).
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.VERIFYING_DEPARTURE, policy.state)
        val actions = feed(260, motionVariance = 0f, fix = fix(260, lat = 40.0001, speed = 8f, state = DevicePhysicalState.WALKING))
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.VERIFYING_DEPARTURE, policy.state)
    }

    @Test
    fun verifyDoesNotConfirmOnCoarseOutlier() {
        // Review #A: a coarse fix far from the anchor (200 m accuracy, often later excluded as
        // low_accuracy) is indoor multipath, not a departure — it must NOT confirm verification.
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.VERIFYING_DEPARTURE, policy.state)
        val actions = feed(260, motionVariance = 0f, fix = fix(260, lat = 40.02, accuracy = 200f, speed = 0f))
        assertTrue("a coarse outlier must not confirm a departure", actions.isEmpty())
        assertEquals(RecorderState.VERIFYING_DEPARTURE, policy.state)
    }

    @Test
    fun movingSelfDemotesWhenQuietPastTimeout() {
        // The 06-13 drain: stuck in MOVING with AR silent (no STILL edge) while the phone sits still.
        // High power is not self-sustaining -- after MOVING_IDLE_TIMEOUT_MS of no real motion the
        // recorder reverts to the stationary cadence on its own. Coarse fixes keep the cluster detector
        // from firing first, isolating the idle-timeout path.
        policy.onArTransitions(listOf(ArActivity.IN_VEHICLE to true), t0)  // -> MOVING
        assertEquals(RecorderState.MOVING, policy.state)
        var reason: String? = null
        var sec = 10L
        while (sec * 1000 <= Constants.MOVING_IDLE_TIMEOUT_MS + 60_000) {
            val actions = feed(sec, motionVariance = 0f, fix = fix(sec, accuracy = 120f, speed = null, state = DevicePhysicalState.STATIONARY))
            (actions.firstOrNull { it is RecordingAction.EnterStationary } as? RecordingAction.EnterStationary)
                ?.let { reason = it.reason }
            if (policy.state == RecorderState.STATIONARY) break
            sec += 20
        }
        assertEquals("moving_idle_timeout", reason)
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun movingStaysWhileTranslating() {
        // A real trip must NOT self-demote: each progressing fix restarts the quiet clock.
        policy.onArTransitions(listOf(ArActivity.IN_VEHICLE to true), t0)
        for (i in 0 until 40) {  // ~6.5 min of motion, past the timeout
            feed(i * 10L, motionVariance = 3f, fix = fix(i * 10L, lat = 40.0 + i * 0.0008, speed = 8f, state = DevicePhysicalState.WALKING, accuracy = 20f))
        }
        assertEquals(RecorderState.MOVING, policy.state)
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

    // ---- geofence-exit confirmation (the 06-13 drain) -------------------------------------------

    @Test
    fun geofenceExitConfirmsRealDeparture() {
        enterStationary()
        // Trustworthy fix far from the anchor, carrying GPS speed: an unambiguous departure.
        val actions = policy.onGeofenceExit(freshFix(lat = 40.02, speed = 5f), motionVariance = 0f, nowMs = t0 + 250_000)
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "geofence_exit" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun geofenceExitConfirmsDisplacementWithoutSpeed() {
        enterStationary()
        // Trustworthy fix clearly outside the stay but no Doppler speed (e.g. network fix) -> real.
        val actions = policy.onGeofenceExit(freshFix(lat = 40.02, accuracy = 15f, speed = 0f), motionVariance = 0f, nowMs = t0 + 250_000)
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "geofence_exit" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun geofenceExitConfirmsOnImuMotion() {
        enterStationary()
        // Even a coarse fix confirms when the phone is physically shaking — a real walk just began.
        val actions = policy.onGeofenceExit(freshFix(lat = 40.0008, accuracy = 120f, speed = 0f), motionVariance = 5f, nowMs = t0 + 250_000)
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "geofence_exit" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun geofenceExitIgnoresCoarseDrift() {
        enterStationary()
        // Coarse (>gate) outlier, no speed, still IMU: indoor multipath drift, NOT a departure. This
        // is the row that fails if a raw geofence EXIT is trusted again (the 8-16 min MOVING bursts).
        val actions = policy.onGeofenceExit(freshFix(lat = 40.0008, accuracy = 120f, speed = 0f), motionVariance = 0f, nowMs = t0 + 250_000)
        assertTrue("a coarse, motionless drift exit must not enter MOVING", actions.isEmpty())
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun geofenceExitIgnoresPhantomSpeedNearAnchor() {
        enterStationary()
        // Trustworthy accuracy AND high reported speed, but still at the anchor with no net
        // displacement: phantom Doppler, not a departure. Speed alone must not confirm the exit -- this
        // is the row that fails if bare GPS speed is trusted again (the 06-13 phantom-speed bursts).
        val actions = policy.onGeofenceExit(freshFix(lat = 40.0001, accuracy = 12f, speed = 8f), motionVariance = 0f, nowMs = t0 + 250_000)
        assertTrue("phantom speed at the anchor must not confirm a geofence exit", actions.isEmpty())
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun geofenceExitWithoutFreshFixButImuMotionConfirms() {
        enterStationary()
        // The confirming fix request failed (GPS-hostile departure) but the phone is shaking — a real
        // walk. Must NOT be discarded as drift just because the fix was null.
        val actions = policy.onGeofenceExit(freshFix = null, motionVariance = 5f, nowMs = t0 + 250_000)
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "geofence_exit" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun geofenceExitWithoutFreshFixAndStillPhoneKeepsStay() {
        enterStationary()
        // No fix AND a still phone -> can't prove a departure; keep the stay (AR/sig-motion recover).
        val actions = policy.onGeofenceExit(freshFix = null, motionVariance = 0f, nowMs = t0 + 250_000)
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun coarseFastSingleFixDoesNotPromoteFromUnknown() {
        // The 06-13 phantom: a single coarse fix with high reported speed is indoor multipath, not a
        // trip start (GPS reported up to 31 m/s with the centroid fixed at home). Bare Doppler no
        // longer promotes -- it must be corroborated by real translation (see the sustained case).
        val actions = feed(0, fix = fix(0, lat = 40.02, speed = 8f, state = DevicePhysicalState.WALKING, accuracy = 120f))
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.UNKNOWN, policy.state)
    }

    @Test
    fun sustainedFastTrackPromotesFromUnknown() {
        // A real GPS-hostile trip: trustworthy fixes whose position genuinely advances. Net displacement
        // corroborates the speed within the motion window, so it promotes (just not on the first fix).
        var promoted = false
        for (i in 0 until 8) {
            val actions = feed(i * 10L, fix = fix(i * 10L, lat = 40.0 + i * 0.0008, speed = 8f, state = DevicePhysicalState.WALKING, accuracy = 55f))
            if (actions.any { it is RecordingAction.EnterMoving }) promoted = true
        }
        assertTrue("a sustained, progressing track must promote to MOVING", promoted)
        assertEquals(RecorderState.MOVING, policy.state)
    }

    // ---- coarse-fix drift guard on UNKNOWN/MOVING promotion -------------------------------------

    @Test
    fun coarseMovingFixDoesNotPromoteFromUnknown() {
        // A single coarse (>gate) WALKING-classified fix is indoor scatter, not a departure: it must
        // not promote UNKNOWN -> MOVING (where it would pin the high-accuracy cadence).
        val actions = feed(0, fix = fix(0, lat = 40.02, speed = 0f, state = DevicePhysicalState.WALKING, accuracy = 120f))
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.UNKNOWN, policy.state)
    }

    @Test
    fun trustedMovingFixPromotesFromUnknown() {
        // The same departure with a trustworthy fix still promotes — the gate is on quality, not motion.
        val actions = feed(0, fix = fix(0, lat = 40.02, speed = 5f, state = DevicePhysicalState.WALKING, accuracy = 12f))
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "fix_departure" })
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
