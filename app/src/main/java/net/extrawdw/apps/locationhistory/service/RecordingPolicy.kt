package net.extrawdw.apps.locationhistory.service

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.RecorderState
import net.extrawdw.apps.locationhistory.domain.VisitCandidate

/** One delivered fix after classification, fed to the policy for the live state decision. */
data class ClassifiedFix(
    val fix: RecentFix,
    val classifiedState: DevicePhysicalState,
    val isConfident: Boolean,
)

/**
 * A side effect the controller must perform after a policy decision. The policy is pure — no Android,
 * no I/O, no wall clock — so it returns the *what*; [RecordingController] does the *how* (retune the
 * service, arm/clear the geofence, arm/disarm the significant-motion sensor, enqueue maintenance,
 * schedule the verification deadline).
 */
sealed interface RecordingAction {
    /**
     * Drop to the low-power stationary cadence: anchor the stay at [candidate] (or the latest stored
     * fix when null), arm the dwell geofence, and — unless [armSigMotion] is false (in deep Doze the
     * platform owns departure detection) — arm the significant-motion sensor fresh.
     */
    data class EnterStationary(
        val candidate: VisitCandidate?,
        val reason: String,
        val armSigMotion: Boolean = true,
    ) : RecordingAction

    /** Leave to the high-accuracy moving cadence: clear the geofence and disarm significant motion. */
    data class EnterMoving(val reason: String) : RecordingAction

    /** Ambiguous departure hint: switch to the burst cadence and schedule the verification deadline. */
    data class BeginVerifying(val reason: String) : RecordingAction

    /**
     * The verification window elapsed unconfirmed: revert to the stationary cadence and re-arm
     * significant motion with backoff growth (a chronically noisy surface must not flap us awake).
     */
    data class RevertToStationary(val reason: String) : RecordingAction
}

/**
 * The pure decision core of the recorder (refactor plan Phase 2). Given the current [RecorderState]
 * and an incoming event with its evidence, it computes the next state and the [RecordingAction]s the
 * controller must execute. All the rules that historically regressed live here, pinned by
 * `RecordingPolicyTest` instead of only by field debugging:
 *
 *  - **State is decoupled from AR.** The four AR moving activities collapse to one [RecorderState.MOVING];
 *    transport mode stays in per-sample evidence for the timeline layer.
 *  - **Stationary entry is authoritative and quality-ordered.** Doze-idle (platform-confirmed) and the
 *    fix-cluster detector enter STATIONARY directly; when neither fires but the device has clearly
 *    been parked (UNKNOWN for [Constants.UNKNOWN_IDLE_TIMEOUT_MS] with no AR motion, no GPS speed and a
 *    quiet IMU) it self-demotes anyway — so noisy indoor GPS can no longer pin the recorder in the
 *    costly UNKNOWN cadence for hours.
 *  - **Ambiguous departures verify before committing.** AR-walking / significant-motion from a stay go
 *    to [RecorderState.VERIFYING_DEPARTURE] and decide from a short burst of fixes, not one fresh fix.
 *
 * Owns the fix/speed buffers and geometry via the shared [RecordingHeuristics] and the [ArActivityTimeline].
 * Not thread-safe; the controller only ever calls it under its single mutex.
 */
internal class RecordingPolicy(
    private val heuristics: RecordingHeuristics,
) {
    var state: RecorderState = RecorderState.UNKNOWN
        private set

    private val arTimeline = ArActivityTimeline()

    /** When the current UNKNOWN spell began (first event seen while UNKNOWN), for the idle timeout. */
    private var unknownSinceMs: Long? = null

    // --- events ---------------------------------------------------------------------------------

    /**
     * Activity Recognition transitions (most-recent last). Each is recorded on the AR timeline; the
     * latest ENTER drives the decision. STILL is trusted only when recent fixes have settled (a
     * smooth light-rail ride makes AR flap STILL); unambiguous motion (run/cycle/vehicle) leaves the
     * stay immediately; walking from a stay is verified first (it can be in-place dwell jitter).
     */
    fun onArTransitions(transitions: List<Pair<ArActivity, Boolean>>, nowMs: Long): List<RecordingAction> {
        for ((activity, isEnter) in transitions) {
            if (isEnter) arTimeline.recordEnter(activity, nowMs) else arTimeline.recordExit(activity, nowMs)
        }
        // Drive the decision only when the most recent transition is an ENTER (an EXIT just ages the
        // timeline). The transitions are recorded above regardless, so recency stays accurate.
        val last = transitions.lastOrNull() ?: return emptyList()
        if (!last.second) return emptyList()
        return when (last.first) {
            ArActivity.STILL ->
                if (heuristics.recentlyMoving()) emptyList()
                else toStationary(heuristics.stationaryClusterCandidate(), "ar_still")

            ArActivity.WALKING ->
                if (state == RecorderState.STATIONARY) toVerifying("ar_walking") else toMoving("ar_walking")

            ArActivity.RUNNING -> toMoving("ar_running")
            ArActivity.CYCLING -> toMoving("ar_cycling")
            ArActivity.IN_VEHICLE -> toMoving("ar_vehicle")
        }
    }

    /** A batch of classified fixes. Pushes them into the buffer, then re-decides the state. */
    fun onFixes(fixes: List<ClassifiedFix>, motionVariance: Float, nowMs: Long): List<RecordingAction> {
        if (fixes.isEmpty()) return emptyList()
        for (cf in fixes) heuristics.pushFix(cf.fix)
        if (state == RecorderState.UNKNOWN && unknownSinceMs == null) unknownSinceMs = nowMs

        val movingFix = fixes.firstOrNull { it.indicatesMotion() }
        return when (state) {
            RecorderState.STATIONARY -> {
                // Leave only when a delivered fix shows real motion AND isn't drift. Scan the whole
                // batch — a genuine departure later in the batch must not be masked by an earlier
                // in-radius jitter fix (the drift guard still blocks the jitter).
                val departure = fixes.firstOrNull {
                    it.indicatesMotion() &&
                        !heuristics.isDriftAt(
                            RecorderState.STATIONARY,
                            it.fix.lat, it.fix.lon, it.fix.speedMps ?: 0f, motionVariance,
                        )
                }
                if (departure != null) toMoving("fix_departure") else emptyList()
            }

            // Confirm only when a burst fix actually leaves the stay (displaced beyond the noise
            // radius, carries GPS speed, or shakes the phone) — the same geometry the stay uses, NOT
            // the bare classifier verdict. Phantom-Doppler drift at the anchor does not confirm (the
            // deadline reverts instead); a slow, speed-less departure confirms once it clears the radius.
            RecorderState.VERIFYING_DEPARTURE ->
                if (fixes.any {
                        !heuristics.isWithinStay(
                            it.fix.lat, it.fix.lon, it.fix.speedMps ?: 0f, motionVariance,
                        )
                    }
                ) toMoving("verify_confirmed") else emptyList()

            RecorderState.MOVING ->
                if (movingFix != null) emptyList()
                else heuristics.stationaryClusterCandidate()
                    ?.let { toStationary(it, "fix_cluster") } ?: emptyList()

            RecorderState.UNKNOWN -> when {
                movingFix != null -> toMoving("fix_departure")
                else -> heuristics.stationaryClusterCandidate()?.let { toStationary(it, "fix_cluster") }
                    ?: if (unknownIdleElapsed(nowMs) && isQuiet(nowMs, motionVariance))
                        toStationary(null, "unknown_idle_timeout")
                    else emptyList()
            }
        }
    }

    /**
     * A dwell-geofence EXIT fired. The geofence is a *single-fix* trigger — it fires the instant one
     * fix crosses the boundary, which is exactly what indoor GPS multipath throws off a still phone
     * (the 06-13 drain: spurious exits pinned the high-accuracy MOVING cadence for 8-16 min each). So
     * confirm the displacement is real before committing: trust [freshFix] only if it carries motion
     * the geofence can't see (GPS speed, or a shaking phone via [motionVariance]) or is positionally
     * trustworthy AND genuinely outside the stay. A coarse, motionless outlier near the anchor is
     * drift -> keep the stay (the controller re-arms the geofence). No fresh fix -> can't confirm, so
     * treat as drift too: a real departure keeps generating AR / motion / Doze-exit signals.
     */
    fun onGeofenceExit(freshFix: RecentFix?, motionVariance: Float, nowMs: Long): List<RecordingAction> {
        // A stale exit while already moving (geofences are only armed at a stay) just commits.
        if (state != RecorderState.STATIONARY) return toMoving("geofence_exit")
        val f = freshFix ?: return emptyList()
        val speed = f.speedMps ?: 0f
        val realMotion = speed >= Constants.DRIFT_MOVING_SPEED_MPS ||
            motionVariance >= Constants.DRIFT_MOTION_VARIANCE_CEILING
        val trustedDisplacement =
            (f.accuracyMeters ?: Float.MAX_VALUE) <= Constants.SAMPLE_ACCURACY_GATE_METERS &&
                !heuristics.isWithinStay(f.lat, f.lon, speed, motionVariance)
        return if (realMotion || trustedDisplacement) toMoving("geofence_exit") else emptyList()
    }

    /**
     * The significant-motion sensor fired while parked — a cheap, Doze-surviving *hint* that the user
     * may have started moving. Not trusted on its own (it fires on transients); verify from a burst.
     */
    fun onSignificantMotion(nowMs: Long): List<RecordingAction> =
        if (state != RecorderState.STATIONARY) emptyList() else toVerifying("significant_motion")

    /**
     * Doze idle-mode changed. Entering deep idle is the platform confirming durable stationarity
     * (motion-gated) — a stronger signal than our sensor — so drop to STATIONARY directly (and the
     * controller disarms the now-redundant sensor). Exiting with real motion is a departure; exiting
     * without is a maintenance-window/screen-on wake (the controller just restores the sensor).
     */
    fun onDozeIdle(idle: Boolean, motionVariance: Float, nowMs: Long): List<RecordingAction> {
        if (idle) {
            // Nothing buffered yet to anchor a stay (e.g. Doze right after a cold start) -> leave it
            // to the next fix; entering stationary with no anchor would also force a GPS bootstrap
            // during deep idle, which we must not do.
            if (state == RecorderState.STATIONARY || heuristics.lastFixLatLon() == null) return emptyList()
            return toStationary(heuristics.stationaryClusterCandidate(), "doze_idle", armSigMotion = false)
        }
        if (state != RecorderState.STATIONARY) return emptyList()
        return if (motionVariance >= Constants.DRIFT_MOTION_VARIANCE_CEILING)
            toMoving("doze_exit_motion") else emptyList()
    }

    /** The verification window elapsed: if still unconfirmed, revert to the stay. */
    fun onVerifyDeadline(nowMs: Long): List<RecordingAction> =
        if (state == RecorderState.VERIFYING_DEPARTURE) revertToStationary("verify_timeout") else emptyList()

    /**
     * Process-restart repair: recent *stored* fixes already prove a stay (the session came back in
     * UNKNOWN with AR silent). Replace the buffer with them and, if they cluster, enter STATIONARY —
     * so a START_STICKY restart / boot while parked drops to low power immediately instead of holding
     * UNKNOWN until the next departure.
     */
    fun onResumeRepair(storedFixes: List<RecentFix>, nowMs: Long): List<RecordingAction> {
        if (storedFixes.size < 3 || !storedFixes.last().stationary) return emptyList()
        // Already stationary -> nothing to repair; return before touching the buffer so we don't
        // clobber the live fixes with stored ones (this path is also reached from foreground resume).
        if (state == RecorderState.STATIONARY) return emptyList()
        heuristics.replaceFixes(storedFixes)
        return heuristics.stationaryClusterCandidate()?.let { toStationary(it, "resume_repair") } ?: emptyList()
    }

    // --- transitions ----------------------------------------------------------------------------

    private fun toStationary(
        candidate: VisitCandidate?,
        reason: String,
        armSigMotion: Boolean = true,
    ): List<RecordingAction> {
        if (state == RecorderState.STATIONARY) return emptyList()
        state = RecorderState.STATIONARY
        unknownSinceMs = null
        // Anchor the drift guard. Prefer the cluster centroid; otherwise the latest buffered fix
        // (Doze/idle-timeout paths); null leaves drift undecidable, which never *suppresses* a departure.
        heuristics.stationaryAnchor =
            candidate?.let { it.centroidLatitude to it.centroidLongitude } ?: heuristics.lastFixLatLon()
        return listOf(RecordingAction.EnterStationary(candidate, reason, armSigMotion))
    }

    private fun toMoving(reason: String): List<RecordingAction> {
        if (state == RecorderState.MOVING) return emptyList()
        state = RecorderState.MOVING
        unknownSinceMs = null
        heuristics.stationaryAnchor = null
        return listOf(RecordingAction.EnterMoving(reason))
    }

    private fun toVerifying(reason: String): List<RecordingAction> {
        if (state == RecorderState.VERIFYING_DEPARTURE) return emptyList()
        state = RecorderState.VERIFYING_DEPARTURE
        unknownSinceMs = null
        return listOf(RecordingAction.BeginVerifying(reason))
    }

    private fun revertToStationary(reason: String): List<RecordingAction> {
        state = RecorderState.STATIONARY
        unknownSinceMs = null
        heuristics.stationaryAnchor = heuristics.lastFixLatLon()
        return listOf(RecordingAction.RevertToStationary(reason))
    }

    // --- predicates -----------------------------------------------------------------------------

    private fun unknownIdleElapsed(nowMs: Long): Boolean =
        unknownSinceMs?.let { nowMs - it >= Constants.UNKNOWN_IDLE_TIMEOUT_MS } ?: false

    /** No AR motion recently, no GPS-derived movement, and a still IMU -> the device is just parked. */
    private fun isQuiet(nowMs: Long, motionVariance: Float): Boolean =
        !arTimeline.anyMovingActiveWithin(nowMs, Constants.UNKNOWN_IDLE_TIMEOUT_MS) &&
            !heuristics.recentlyMoving() &&
            motionVariance < Constants.DRIFT_MOTION_VARIANCE_CEILING

    /**
     * A delivered fix that credibly shows motion: positionally trustworthy (accuracy within the
     * sample gate, so 100-200 m indoor multipath scatter can't fake a departure) AND either carrying
     * GPS speed or a confident non-stationary classification. The accuracy gate is the drift guard for
     * the UNKNOWN -> MOVING promotion and the MOVING -> STATIONARY demotion, mirroring what
     * [RecordingHeuristics.isDriftAt] does for the STATIONARY branch: a single coarse outlier can no
     * longer claim movement and pin the costly high-accuracy cadence (the 06-13 idle drain).
     */
    private fun ClassifiedFix.indicatesMotion(): Boolean =
        classifiedState != DevicePhysicalState.STATIONARY &&
            (fix.accuracyMeters ?: Float.MAX_VALUE) <= Constants.SAMPLE_ACCURACY_GATE_METERS &&
            (isConfident || (fix.speedMps ?: 0f) >= 1.0f)
}
