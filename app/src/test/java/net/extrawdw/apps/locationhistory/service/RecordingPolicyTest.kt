package net.extrawdw.apps.locationhistory.service

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.RecorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table tests for [RecordingPolicy] — the pure recorder state machine.
 *
 * Each scenario is drawn from a real device log, encoded so a regression reproduces as a failing row
 * instead of a multi-hour battery dump. The departure path is now a two-tier escalation ladder:
 *  - weak hints (significant-motion, geofence exit, Wi-Fi disconnect, Doze-exit motion) enter the
 *    cheap [RecorderState.SENSING_DEPARTURE] look;
 *  - a fix that actually leaves the stay escalates to [RecorderState.CONFIRMING_DEPARTURE], which
 *    confirms only on a multi-fix movement signature (sustained Doppler or a coherent displacement
 *    trace) — never one fix;
 *  - AR movement (incl. walking) is trusted and goes straight to MOVING.
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

    @Test
    fun dozeExitMotionStartsSensing() {
        enterStationary()
        // Doze exit + IMU motion is a hint, not a confirmation: it enters the cheap SENSING look.
        val actions = policy.onDozeIdle(idle = false, motionVariance = 5f, nowMs = t0 + 250_000)
        assertTrue(actions.any { it is RecordingAction.BeginVerifying && it.reason == "doze_exit_motion" })
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
    }

    @Test
    fun dozeExitWithoutMotionStaysStationary() {
        enterStationary()
        // A still Doze exit (maintenance-window / screen-on wake) is no departure -> stay parked.
        val actions = policy.onDozeIdle(idle = false, motionVariance = 0f, nowMs = t0 + 250_000)
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun dozeEntryDuringMovingDemotesToStationary() {
        // Doze ENTER is the platform confirming durable stationarity -> it must demote MOVING
        // immediately, independent of the keep-alive (a hard backstop against any pinned high cadence).
        feed(0, fix = fix(0)) // buffer a fix so a stay can be anchored
        policy.onArTransitions(listOf(ArActivity.IN_VEHICLE to true), t0 + 1_000) // -> MOVING
        assertEquals(RecorderState.MOVING, policy.state)
        val actions = policy.onDozeIdle(idle = true, motionVariance = 0f, nowMs = t0 + 2_000)
        assertTrue(actions.any { it is RecordingAction.EnterStationary && it.reason == "doze_idle" })
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun dozeEntryDuringVerifyCancelsAndDemotes() {
        // Doze ENTER while verifying cancels the in-flight look and drops to the stay.
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000) // -> SENSING
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
        val actions = policy.onDozeIdle(idle = true, motionVariance = 0f, nowMs = t0 + 251_000)
        assertTrue(actions.any { it is RecordingAction.EnterStationary })
        assertEquals(RecorderState.STATIONARY, policy.state)
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
        // AR reports STILL while GPS fakes speed on a motionless phone (centroid fixed). With bare speed
        // no longer trusted as translation, the correct STILL is accepted -> low power.
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
    fun arWalkingFromStationaryEntersMovingDirectly() {
        // AR is trusted as a real user-movement signal: walking fires MOVING directly (no verify look).
        // In-place pacing is recorded and the timeline merges it back into the visit.
        feed(0, fix = fix(0))
        policy.onArTransitions(listOf(ArActivity.STILL to true), t0 + 1_000)
        assertEquals(RecorderState.STATIONARY, policy.state)
        val actions = policy.onArTransitions(listOf(ArActivity.WALKING to true), t0 + 2_000)
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "ar_walking" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun arWalkingDuringSensingPromotesToMoving() {
        // AR walking mid-verify is trusted and promotes straight to MOVING (the weak hint that started
        // SENSING does not — but AR does).
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
        val actions = policy.onArTransitions(listOf(ArActivity.WALKING to true), t0 + 251_000)
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "ar_walking" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun arStillDoesNotAbortDepartureVerification() {
        enterStationary()
        assertTrue(policy.onSignificantMotion(t0 + 250_000).any { it is RecordingAction.BeginVerifying })
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)

        val actions = policy.onArTransitions(listOf(ArActivity.STILL to true), t0 + 251_000)

        assertTrue("STILL must not abort the in-flight verify look", actions.isEmpty())
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
    }

    // ---- departure verification ladder ----------------------------------------------------------

    @Test
    fun significantMotionStartsSensing() {
        enterStationary()
        val actions = policy.onSignificantMotion(t0 + 250_000)
        assertTrue(actions.any { it is RecordingAction.BeginVerifying && it.reason == "significant_motion" })
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
    }

    @Test
    fun departureVerifyConfirmsOnSustainedDoppler() {
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
        // A displaced fix escalates SENSING -> CONFIRMING; then several trusted Doppler fixes confirm.
        val escalate = feed(251, fix = fix(251, lat = 40.001, speed = 5f, state = DevicePhysicalState.WALKING))
        assertTrue(escalate.any { it is RecordingAction.BeginVerifying })
        assertEquals(RecorderState.CONFIRMING_DEPARTURE, policy.state)
        feed(252, fix = fix(252, lat = 40.0012, speed = 5f, state = DevicePhysicalState.WALKING))
        val actions = feed(253, fix = fix(253, lat = 40.0014, speed = 5f, state = DevicePhysicalState.WALKING))
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "verify_confirmed" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun verifyConfirmsOnCoherentDisplacementWithoutSpeed() {
        // A slow departure with no Doppler (network fixes): a sustained, progressing, kinematically
        // plausible displacement trace away from the anchor confirms even at speed 0.
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
        val escalate = feed(260, fix = fix(260, lat = 40.001, speed = 0f, state = DevicePhysicalState.WALKING))
        assertTrue(escalate.any { it is RecordingAction.BeginVerifying })
        assertEquals(RecorderState.CONFIRMING_DEPARTURE, policy.state)
        feed(290, fix = fix(290, lat = 40.0015, speed = 0f, state = DevicePhysicalState.WALKING))
        feed(320, fix = fix(320, lat = 40.002, speed = 0f, state = DevicePhysicalState.WALKING))
        val actions = feed(350, fix = fix(350, lat = 40.0025, speed = 0f, state = DevicePhysicalState.WALKING))
        assertTrue(actions.any { it is RecordingAction.EnterMoving && it.reason == "verify_confirmed" })
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun confirmRejectsTeleportJumpWithoutDoppler() {
        // A no-Doppler displacement whose fix-to-fix steps imply an implausible speed is multipath, not
        // a walk-out -- the kinematic guard rejects it (stays CONFIRMING until the deadline reverts).
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        feed(260, fix = fix(260, lat = 40.001, speed = 0f, state = DevicePhysicalState.WALKING)) // escalate
        assertEquals(RecorderState.CONFIRMING_DEPARTURE, policy.state)
        feed(261, fix = fix(261, lat = 40.003, speed = 0f, state = DevicePhysicalState.WALKING))
        feed(262, fix = fix(262, lat = 40.005, speed = 0f, state = DevicePhysicalState.WALKING))
        val actions = feed(263, fix = fix(263, lat = 40.007, speed = 0f, state = DevicePhysicalState.WALKING))
        assertTrue("a no-Doppler teleport jump must not confirm", actions.none { it is RecordingAction.EnterMoving })
        assertEquals(RecorderState.CONFIRMING_DEPARTURE, policy.state)
    }

    @Test
    fun confirmDoesNotConfirmOnPhantomDopplerNearAnchor() {
        // In CONFIRMING, accurate near-anchor fixes carrying phantom Doppler (no displacement) must not
        // confirm: the Doppler branch requires real displacement from the frozen anchor, so 3 in-place
        // phantom-Doppler fixes can't promote to MOVING.
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        // Escalate via a coarse, far fix (which the <=30 m confirm evidence then excludes).
        feed(260, fix = fix(260, lat = 40.02, accuracy = 200f, speed = 0f, state = DevicePhysicalState.WALKING))
        assertEquals(RecorderState.CONFIRMING_DEPARTURE, policy.state)
        feed(262, fix = fix(262, lat = 40.0001, accuracy = 10f, speed = 8f, state = DevicePhysicalState.WALKING))
        feed(264, fix = fix(264, lat = 40.0001, accuracy = 10f, speed = 8f, state = DevicePhysicalState.WALKING))
        val actions = feed(266, fix = fix(266, lat = 40.0001, accuracy = 10f, speed = 8f, state = DevicePhysicalState.WALKING))
        assertTrue("phantom Doppler near the anchor must not confirm", actions.none { it is RecordingAction.EnterMoving })
        assertEquals(RecorderState.CONFIRMING_DEPARTURE, policy.state)
    }

    @Test
    fun verifyDoesNotEscalateOnDriftFix() {
        // Phantom GPS drift near the anchor (no real displacement) must NOT escalate the cheap look.
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
        val actions = feed(260, motionVariance = 0f, fix = fix(260, lat = 40.0001, speed = 0.5f, state = DevicePhysicalState.WALKING))
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
    }

    @Test
    fun verifyDoesNotEscalateOnPhantomSpeedAtAnchor() {
        // Phantom Doppler at the anchor (near, high reported speed, no net displacement) must NOT
        // escalate -- bare GPS speed no longer leaves the stay (the 06-13 phantom exits).
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
        val actions = feed(260, motionVariance = 0f, fix = fix(260, lat = 40.0001, speed = 8f, state = DevicePhysicalState.WALKING))
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
    }

    @Test
    fun verifyDoesNotConfirmOnCoarseOutlier() {
        // A coarse fix far from the anchor (200 m accuracy) may escalate the cheap look (it is displaced
        // beyond its own error), but the accuracy-gated confirm never trusts it: a single coarse outlier
        // can never reach MOVING -- CONFIRMING gathers real fixes or reverts.
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
        val actions = feed(260, motionVariance = 0f, fix = fix(260, lat = 40.02, accuracy = 200f, speed = 0f, state = DevicePhysicalState.WALKING))
        assertTrue("a coarse outlier must not confirm to MOVING", actions.none { it is RecordingAction.EnterMoving })
        assertTrue("must not reach MOVING from one coarse outlier", policy.state != RecorderState.MOVING)
    }

    @Test
    fun departureVerifyTimesOut() {
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
        val actions = policy.onVerifyDeadline(t0 + 250_000 + Constants.SENSING_VERIFY_WINDOW_MS)
        assertTrue(actions.any { it is RecordingAction.RevertToStationary })
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun confirmingTimesOutWhenUnconfirmed() {
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        feed(260, fix = fix(260, lat = 40.001, speed = 0f, state = DevicePhysicalState.WALKING)) // -> CONFIRMING
        assertEquals(RecorderState.CONFIRMING_DEPARTURE, policy.state)
        val actions = policy.onVerifyDeadline(t0 + 260_000 + Constants.CONFIRMING_VERIFY_WINDOW_MS)
        assertTrue(actions.any { it is RecordingAction.RevertToStationary })
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun significantMotionIgnoredWhenNotStationary() {
        assertTrue(policy.onSignificantMotion(t0).isEmpty()) // starts UNKNOWN
        assertEquals(RecorderState.UNKNOWN, policy.state)
    }

    @Test
    fun movingSelfDemotesWhenQuietPastTimeout() {
        // The 06-13 drain: stuck in MOVING with AR silent (no STILL edge) while the phone sits still.
        // High power is not self-sustaining -- after MOVING_IDLE_TIMEOUT_MS of no real motion (no
        // moving fix, no steps, no sustained Doppler) the recorder reverts on its own. Coarse,
        // speed-less fixes keep the cluster detector and the Doppler keep-alive from firing first.
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
    fun movingStaysOnSustainedSlowDoppler() {
        // The slow-trip self-demote fix: a steady ~1 m/s trip (below the recentlyMoving floor) that AR
        // and the classifier mislabel STATIONARY, with no steps, must NOT demote mid-trip -- sustained
        // Doppler holds the quiet clock alive. The fixes progress (they never cluster), so the
        // cluster/idle fallbacks don't fire either.
        policy.onArTransitions(listOf(ArActivity.IN_VEHICLE to true), t0)  // -> MOVING
        var sec = 0L
        while (sec * 1000 <= Constants.MOVING_IDLE_TIMEOUT_MS + 60_000) {
            // ~1 m/s northward (10 m / 10 s), classified STATIONARY so ONLY Doppler can keep MOVING.
            feed(sec, fix = fix(sec, lat = 40.0 + sec * 0.0000090, speed = 1.0f, accuracy = 20f, state = DevicePhysicalState.STATIONARY))
            sec += 10
        }
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun movingDemotesOnPhantomDopplerAtFixedSpot() {
        // The 06-13 drain class: phantom GPS Doppler (high speed) on a MOTIONLESS phone at a FIXED
        // centroid must NOT pin MOVING. The keep-alive requires real net translation, so a pinned
        // centroid (net ~0) self-demotes despite sustained >=0.8 m/s phantom speed on accurate (<=30 m)
        // fixes -- and the cluster detector can't catch it (its meanSpeed>0.6 bail is tripped by the
        // very spikes), so the net-translation guard in the keep-alive is what saves it.
        policy.onArTransitions(listOf(ArActivity.IN_VEHICLE to true), t0)  // -> MOVING
        var demoted = false
        var sec = 0L
        while (sec * 1000 <= Constants.MOVING_IDLE_TIMEOUT_MS + 180_000) {
            // Fixed lat/lon, accuracy 20, classified STATIONARY; ~1-in-3 phantom spikes at 3 m/s.
            val spd = if (sec % 30 == 0L) 3.0f else 0.2f
            val actions = feed(sec, fix = fix(sec, lat = 40.0, lon = -74.0, speed = spd, accuracy = 20f, state = DevicePhysicalState.STATIONARY))
            if (actions.any { it is RecordingAction.EnterStationary }) demoted = true
            if (policy.state == RecorderState.STATIONARY) break
            sec += 10
        }
        assertTrue("phantom Doppler at a fixed centroid must self-demote", demoted)
        assertEquals(RecorderState.STATIONARY, policy.state)
    }

    @Test
    fun movingKeepAliveSelfDemotesAfterMovementStops() {
        // The keep-alive must not pin MOVING with NO external signal: it only holds while the device
        // keeps translating. Once movement stops, net translation drains from the window, the keep-alive
        // can no longer fire, and the recorder self-demotes (cluster / idle-timeout) without any AR /
        // sig-motion / geofence / Doze input.
        policy.onArTransitions(listOf(ArActivity.IN_VEHICLE to true), t0) // -> MOVING
        // Phase 1: ~1 m/s progressing for 3 min -> sustained Doppler holds MOVING.
        var sec = 0L
        while (sec <= 180) {
            feed(sec, fix = fix(sec, lat = 40.0 + sec * 0.0000090, speed = 1.0f, accuracy = 20f, state = DevicePhysicalState.STATIONARY))
            sec += 10
        }
        assertEquals(RecorderState.MOVING, policy.state)
        // Phase 2: device stops; keep feeding fixes at the last position (no net translation).
        val stopLat = 40.0 + 180 * 0.0000090
        var demoted = false
        val limit = 180 + (Constants.MOVING_IDLE_TIMEOUT_MS / 1000) + 300
        while (sec <= limit) {
            val actions = feed(sec, fix = fix(sec, lat = stopLat, speed = 0.2f, accuracy = 20f, state = DevicePhysicalState.STATIONARY))
            if (actions.any { it is RecordingAction.EnterStationary }) demoted = true
            if (policy.state == RecorderState.STATIONARY) break
            sec += 10
        }
        assertTrue("MOVING must self-demote after movement stops, with no external signal", demoted)
        assertEquals(RecorderState.STATIONARY, policy.state)
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

    // ---- geofence / Wi-Fi hints -----------------------------------------------------------------

    @Test
    fun geofenceExitStartsSensing() {
        enterStationary()
        val actions = policy.onGeofenceExit(t0 + 250_000)
        assertTrue(actions.any { it is RecordingAction.BeginVerifying && it.reason == "geofence_exit" })
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
    }

    @Test
    fun geofenceExitIgnoredWhileMoving() {
        // A stale exit while already moving (geofences are only armed at a stay) must not re-confirm.
        policy.onArTransitions(listOf(ArActivity.IN_VEHICLE to true), t0)
        assertEquals(RecorderState.MOVING, policy.state)
        val actions = policy.onGeofenceExit(t0 + 1_000)
        assertTrue(actions.isEmpty())
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun wifiDisconnectStartsSensing() {
        enterStationary()
        val actions = policy.onWifiDisconnected(t0 + 250_000)
        assertTrue(actions.any { it is RecordingAction.BeginVerifying && it.reason == "wifi_disconnected" })
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
    }

    @Test
    fun wifiDisconnectIgnoredWhenNotStationary() {
        policy.onArTransitions(listOf(ArActivity.IN_VEHICLE to true), t0)
        assertTrue(policy.onWifiDisconnected(t0 + 1_000).isEmpty())
        assertEquals(RecorderState.MOVING, policy.state)
    }

    @Test
    fun secondHintDuringVerifyIsNoOp() {
        // Once verifying, a second weak hint (geofence / Wi-Fi) must not re-confirm or reset the look.
        enterStationary()
        policy.onSignificantMotion(t0 + 250_000)
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
        assertTrue(policy.onGeofenceExit(t0 + 251_000).isEmpty())
        assertTrue(policy.onWifiDisconnected(t0 + 252_000).isEmpty())
        assertEquals(RecorderState.SENSING_DEPARTURE, policy.state)
    }

    // ---- coarse-fix drift guard on UNKNOWN/MOVING promotion -------------------------------------

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
