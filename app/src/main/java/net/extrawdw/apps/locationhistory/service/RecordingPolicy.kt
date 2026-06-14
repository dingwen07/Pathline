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
 *    fix-cluster detector enter STATIONARY directly; when neither fires, the recorder self-demotes from
 *    UNKNOWN/MOVING once it has gone quiet (no net displacement and no steps) for the state's idle
 *    budget — keyed on real translation, NOT on raw IMU energy or stale, lossy AR-moving edges (review
 *    findings #1/#2). No departure hint can hold the costly high cadence indefinitely; only continuous
 *    movement does.
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

    /**
     * When the current quiet spell began -- the first fix seen in a non-STATIONARY state with no
     * confirmed motion since. Drives the idle-timeout self-demote that returns the recorder to low
     * power when a departure hint (geofence / significant-motion) or an AR-jitter / phantom-speed
     * spell is not sustained by real movement. Reset to the current time whenever a fix actually shows
     * motion, and cleared on entering STATIONARY. This is the architectural backstop behind
     * AR-as-authority: NO hint can hold the high-power cadence indefinitely -- only continuous
     * AR-moving or net displacement keeps it alive (the 06-13 multi-hour drains).
     */
    private var quietSinceMs: Long? = null

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

    /** A batch of classified fixes. Pushes them into the buffer, then re-decides the state.
     *  [stepDelta] is steps counted in this batch — motion evidence that holds off the self-demote. */
    fun onFixes(
        fixes: List<ClassifiedFix>,
        motionVariance: Float,
        nowMs: Long,
        stepDelta: Int = 0,
    ): List<RecordingAction> {
        if (fixes.isEmpty()) return emptyList()
        for (cf in fixes) heuristics.pushFix(cf.fix)
        if (state != RecorderState.STATIONARY && quietSinceMs == null) quietSinceMs = nowMs

        val movingFix = fixes.firstOrNull { it.indicatesMotion() }
        // Real translation (a moving fix) or steps restart the quiet budget — motion evidence that,
        // unlike raw IMU energy, is not faked by a vibrating-but-stationary surface (an idling car).
        if (movingFix != null || stepDelta > 0) quietSinceMs = nowMs
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
                        // A coarse outlier (often later excluded as low_accuracy) is indoor multipath,
                        // not a departure — only a positionally-trustworthy fix may confirm.
                        (it.fix.accuracyMeters ?: Float.MAX_VALUE) <= Constants.SAMPLE_ACCURACY_GATE_METERS &&
                            !heuristics.isWithinStay(
                                it.fix.lat, it.fix.lon, it.fix.speedMps ?: 0f, motionVariance,
                            )
                    }
                ) toMoving("verify_confirmed") else emptyList()

            // High-power MOVING is not self-sustaining: it must be continuously justified by real
            // motion (a moving fix or steps — both reset the quiet clock above). Otherwise we try to
            // settle (cluster) and, failing that, self-demote once quiet for MOVING_IDLE_TIMEOUT_MS --
            // so a departure hint, a phantom-speed/AR-jitter spell, or mere IMU vibration can no longer
            // pin the costly cadence for hours (06-13), AR-silent or not. The demote keys off the quiet
            // clock (net displacement + steps), NOT a stale AR-moving edge or raw IMU energy.
            RecorderState.MOVING -> when {
                movingFix != null || stepDelta > 0 -> emptyList()
                else -> heuristics.stationaryClusterCandidate()?.let { toStationary(it, "fix_cluster") }
                    ?: if (idleElapsed(nowMs, Constants.MOVING_IDLE_TIMEOUT_MS))
                        toStationary(null, "moving_idle_timeout")
                    else emptyList()
            }

            RecorderState.UNKNOWN -> when {
                movingFix != null -> toMoving("fix_departure")
                else -> heuristics.stationaryClusterCandidate()?.let { toStationary(it, "fix_cluster") }
                    ?: if (idleElapsed(nowMs, Constants.UNKNOWN_IDLE_TIMEOUT_MS))
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
     * drift -> keep the stay (the controller re-arms the geofence). A missing fix is NOT auto-drift: a
     * shaking phone still confirms (below), and a still phone with no fix falls back to the slower
     * AR / significant-motion / Doze-exit signals rather than bursting GPS on every spurious exit.
     */
    fun onGeofenceExit(freshFix: RecentFix?, motionVariance: Float, nowMs: Long): List<RecordingAction> {
        // A stale exit while already moving (geofences are only armed at a stay) just commits.
        if (state != RecorderState.STATIONARY) return toMoving("geofence_exit")
        // A physically shaking phone is real motion the geofence can't see — and, crucially, it
        // confirms even when the confirmatory fix request failed (GPS-hostile departure). Check it
        // BEFORE the null-fix bailout so a real walk out of a dead zone is not discarded as drift.
        if (motionVariance >= Constants.DRIFT_MOTION_VARIANCE_CEILING) return toMoving("geofence_exit")
        val f = freshFix ?: return emptyList()
        // A single GPS-speed reading is NOT enough to confirm -- indoor multipath fakes Doppler on a
        // still phone (the 06-13 phantom-speed exits). Confirm only on a positionally-trustworthy fix
        // that has actually left the stay: outside the noise radius, or a speed corroborated by net
        // displacement (both via the net-aware isWithinStay). Otherwise stay put and re-arm the fence.
        val trustedDisplacement =
            (f.accuracyMeters ?: Float.MAX_VALUE) <= Constants.SAMPLE_ACCURACY_GATE_METERS &&
                !heuristics.isWithinStay(f.lat, f.lon, f.speedMps ?: 0f, motionVariance)
        return if (trustedDisplacement) toMoving("geofence_exit") else emptyList()
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
        quietSinceMs = null
        // Anchor the drift guard. Prefer the cluster centroid; otherwise the latest buffered fix
        // (Doze/idle-timeout paths); null leaves drift undecidable, which never *suppresses* a departure.
        heuristics.stationaryAnchor =
            candidate?.let { it.centroidLatitude to it.centroidLongitude } ?: heuristics.lastFixLatLon()
        return listOf(RecordingAction.EnterStationary(candidate, reason, armSigMotion))
    }

    private fun toMoving(reason: String): List<RecordingAction> {
        if (state == RecorderState.MOVING) return emptyList()
        state = RecorderState.MOVING
        quietSinceMs = null
        heuristics.stationaryAnchor = null
        return listOf(RecordingAction.EnterMoving(reason))
    }

    private fun toVerifying(reason: String): List<RecordingAction> {
        if (state == RecorderState.VERIFYING_DEPARTURE) return emptyList()
        state = RecorderState.VERIFYING_DEPARTURE
        quietSinceMs = null
        return listOf(RecordingAction.BeginVerifying(reason))
    }

    private fun revertToStationary(reason: String): List<RecordingAction> {
        state = RecorderState.STATIONARY
        quietSinceMs = null
        heuristics.stationaryAnchor = heuristics.lastFixLatLon()
        return listOf(RecordingAction.RevertToStationary(reason))
    }

    // --- predicates -----------------------------------------------------------------------------

    private fun idleElapsed(nowMs: Long, timeoutMs: Long): Boolean =
        quietSinceMs?.let { nowMs - it >= timeoutMs } ?: false

    /**
     * A delivered fix that credibly shows motion, used to gate the UNKNOWN -> MOVING promotion, the
     * STATIONARY departure (which adds [RecordingHeuristics.isDriftAt]) and the MOVING-stay decision.
     * Two signals, deliberately skeptical of bare GPS:
     *  - **Real translation** ([RecordingHeuristics.recentlyMoving]) -- the position actually progressed
     *    beyond the noise radius. This is the honest movement signal and the override: it counts even
     *    when AR/the classifier says STILL (a smooth ride AR mislabels STILL), and is never faked by
     *    indoor multipath, which oscillates in place (the 06-13 phantom: 31 m/s with a fixed centroid).
     *  - A **confident moving classifier verdict** (in practice an AR-strong moving activity) that is
     *    positionally trustworthy -- accuracy within the sample gate -- for the trip start before net
     *    displacement has accumulated. Bare GPS speed and a STILL verdict do NOT promote.
     */
    private fun ClassifiedFix.indicatesMotion(): Boolean {
        if (heuristics.recentlyMoving()) return true
        if (classifiedState == DevicePhysicalState.STATIONARY) return false
        return isConfident && (fix.accuracyMeters ?: Float.MAX_VALUE) <= Constants.SAMPLE_ACCURACY_GATE_METERS
    }
}
