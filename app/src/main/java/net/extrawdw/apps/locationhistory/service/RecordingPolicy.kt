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

    /**
     * Entered (or escalated within) departure verification: retune to the new state's cadence and
     * (re)schedule the verification deadline for [windowMs]. Covers both SENSING_DEPARTURE entry and the
     * SENSING -> CONFIRMING_DEPARTURE escalation; the policy has already set the target state.
     */
    data class BeginVerifying(val reason: String, val windowMs: Long) : RecordingAction

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
 *  - **Ambiguous departures verify before committing.** Weak hints (significant-motion, geofence exit,
 *    Wi-Fi disconnect, Doze-exit motion) enter [RecorderState.SENSING_DEPARTURE] (a cheap BALANCED look)
 *    and escalate to [RecorderState.CONFIRMING_DEPARTURE] only on real displacement/Doppler, deciding
 *    from a multi-fix signature, not one fresh fix. AR movement (incl. walking) is trusted -> MOVING.
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

    /**
     * When departure verification began (SENSING entry). The confirm rule reads only fixes received
     * since this instant -- earlier samples were taken under the stationary low-power request and just
     * prove the pre-existing stay. Kept across the SENSING -> CONFIRMING escalation; cleared on leaving
     * verification (to MOVING or back to STATIONARY).
     */
    private var verifyEntryMs: Long? = null

    private fun isVerifying(): Boolean =
        state == RecorderState.SENSING_DEPARTURE || state == RecorderState.CONFIRMING_DEPARTURE

    // --- events ---------------------------------------------------------------------------------

    /**
     * Activity Recognition transitions (most-recent last). Each is recorded on the AR timeline; the
     * latest ENTER drives the decision. STILL is trusted only when recent fixes have settled and no
     * departure verification is in flight (a smooth light-rail ride makes AR flap STILL); all AR motion
     * (walk/run/cycle/vehicle) is trusted and leaves the stay immediately -- the timeline later merges
     * any in-place dwell walking back into the visit.
     */
    fun onArTransitions(
        transitions: List<Pair<ArActivity, Boolean>>,
        nowMs: Long
    ): List<RecordingAction> {
        for ((activity, isEnter) in transitions) {
            if (isEnter) arTimeline.recordEnter(
                activity,
                nowMs
            ) else arTimeline.recordExit(activity, nowMs)
        }
        // Drive the decision only when the most recent transition is an ENTER (an EXIT just ages the
        // timeline). The transitions are recorded above regardless, so recency stays accurate.
        val last = transitions.lastOrNull() ?: return emptyList()
        if (!last.second) return emptyList()
        return when (last.first) {
            ArActivity.STILL ->
                if (isVerifying() || heuristics.recentlyMoving()) emptyList()
                else toStationary(heuristics.stationaryClusterCandidate(), "ar_still")

            // AR movement is trusted as a real user-movement signal -> MOVING directly, including
            // walking (in-place pacing at home is recorded and the timeline merges it later). AR also
            // promotes straight out of a verify look, so a real walk-out is caught even mid-SENSING.
            ArActivity.WALKING -> toMoving("ar_walking")
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
        // A slow real trip (below the ~1.8 m/s effective recentlyMoving floor) with no steps still
        // carries sustained Doppler; count it as motion so such a trip neither demotes mid-trip nor
        // loses its quiet budget. Accuracy-gated + multi-fix, so it is not faked by one phantom spike.
        val sustainedDoppler = heuristics.sustainedDopplerMoving()
        // Real translation (a moving fix), sustained Doppler, or steps restart the quiet budget — motion
        // evidence that, unlike raw IMU energy, is not faked by a vibrating-but-stationary surface.
        if (movingFix != null || stepDelta > 0 || sustainedDoppler) quietSinceMs = nowMs
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

            // Cheap first look: escalate to the HIGH_ACCURACY confirm only when a fix is displaced
            // beyond BOTH the noise radius AND its own accuracy -- a real, large move even a coarse
            // BALANCED fix can attest to. Position only: IMU energy (a phone pickup) and bare Doppler at
            // the anchor (a phantom spike) do NOT escalate, so the cheap tier filters those for free. No
            // hint -> wait for more fixes or the SENSING deadline (which reverts to STATIONARY).
            RecorderState.SENSING_DEPARTURE -> {
                val hint = fixes.any {
                    heuristics.hasLeftStayBeyondAccuracy(it.fix.lat, it.fix.lon, it.fix.accuracyMeters)
                }
                if (hint) toConfirming("sensing_escalate", nowMs) else emptyList()
            }

            // Escalated confirm: leave to MOVING only on a multi-fix movement signature over the verify
            // window (sustained Doppler OR a coherent, kinematically-plausible displacement trace from
            // the frozen anchor) -- never one fix, one Doppler spike, or IMU energy. Otherwise wait, and
            // the CONFIRMING deadline reverts to the stay.
            RecorderState.CONFIRMING_DEPARTURE ->
                if (confirmsDeparture(heuristics.departureEvidenceSince(verifyEntryMs ?: nowMs)))
                    toMoving("verify_confirmed") else emptyList()

            // High-power MOVING is not self-sustaining: it must be continuously justified by real
            // motion (a moving fix or steps — both reset the quiet clock above). Otherwise we try to
            // settle (cluster) and, failing that, self-demote once quiet for MOVING_IDLE_TIMEOUT_MS --
            // so a departure hint, a phantom-speed/AR-jitter spell, or mere IMU vibration can no longer
            // pin the costly cadence for hours (06-13), AR-silent or not. The demote keys off the quiet
            // clock (net displacement + steps), NOT a stale AR-moving edge or raw IMU energy.
            RecorderState.MOVING -> when {
                movingFix != null || stepDelta > 0 -> emptyList()
                // Sustained Doppler reset the quiet clock above, so the idle-timeout can't fire on a
                // slow real trip. The cluster detector is left as the backstop AHEAD of the timeout: a
                // genuinely clustered (stationary) device still demotes via fix_cluster even if GPS fakes
                // speed, so phantom Doppler can't pin MOVING -- only a non-clustering, progressing trip
                // (whose fixes never settle within the stay radius) keeps the cadence alive.
                else -> heuristics.stationaryClusterCandidate()
                    ?.let { toStationary(it, "fix_cluster") }
                    ?: if (idleElapsed(nowMs, Constants.MOVING_IDLE_TIMEOUT_MS))
                        toStationary(null, "moving_idle_timeout")
                    else emptyList()
            }

            RecorderState.UNKNOWN -> when {
                movingFix != null -> toMoving("fix_departure")
                else -> heuristics.stationaryClusterCandidate()
                    ?.let { toStationary(it, "fix_cluster") }
                    ?: if (idleElapsed(nowMs, Constants.UNKNOWN_IDLE_TIMEOUT_MS))
                        toStationary(null, "unknown_idle_timeout")
                    else emptyList()
            }
        }
    }

    /**
     * A dwell-geofence EXIT fired -- a weak departure hint. The geofence is a *single-fix* trigger (it
     * fires the instant one fix crosses the boundary, exactly what indoor multipath throws off a still
     * phone -- the 06-13 drain that pinned high-accuracy MOVING for 8-16 min each). So it never confirms
     * directly: enter the cheap [RecorderState.SENSING_DEPARTURE] look and let the gathered fixes
     * decide. A stale exit while not parked -- or a second exit while already verifying/moving -- is a
     * no-op (never a re-confirm). The confirmatory fix/IMU sampling the controller used to do inline now
     * lives in the SENSING tier.
     */
    fun onGeofenceExit(nowMs: Long): List<RecordingAction> =
        if (state != RecorderState.STATIONARY) emptyList() else toSensing("geofence_exit", nowMs)

    /**
     * The connected Wi-Fi network dropped while parked -- a near-free departure hint (the phone was
     * carried out of range / off a hotspot). Like the geofence exit it is only a hint (a router reboot
     * or signal flap also disconnects), so it enters the cheap [RecorderState.SENSING_DEPARTURE] look
     * rather than confirming. A no-op when not parked / already verifying.
     */
    fun onWifiDisconnected(nowMs: Long): List<RecordingAction> =
        if (state != RecorderState.STATIONARY) emptyList() else toSensing("wifi_disconnected", nowMs)

    /**
     * The significant-motion sensor fired while parked — a cheap, Doze-surviving *hint* that the user
     * may have started moving. Not trusted on its own (it fires on transients); enter the cheap
     * [RecorderState.SENSING_DEPARTURE] look and let the gathered fixes decide.
     */
    fun onSignificantMotion(nowMs: Long): List<RecordingAction> =
        if (state != RecorderState.STATIONARY) emptyList() else toSensing("significant_motion", nowMs)

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
            return toStationary(
                heuristics.stationaryClusterCandidate(),
                "doze_idle",
                armSigMotion = false
            )
        }
        if (state != RecorderState.STATIONARY) return emptyList()
        // Doze exit with motion is a hint, not a confirmation (a maintenance-window / screen-on wake can
        // coincide with phone handling): enter the cheap SENSING look. Without motion the controller
        // just re-arms the sensor.
        return if (motionVariance >= Constants.DRIFT_MOTION_VARIANCE_CEILING)
            toSensing("doze_exit_motion", nowMs) else emptyList()
    }

    /** The verification window elapsed (SENSING or CONFIRMING): if still unconfirmed, revert to the stay. */
    fun onVerifyDeadline(nowMs: Long): List<RecordingAction> =
        if (isVerifying()) revertToStationary("verify_timeout") else emptyList()

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
        return heuristics.stationaryClusterCandidate()?.let { toStationary(it, "resume_repair") }
            ?: emptyList()
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
        verifyEntryMs = null
        // Anchor the drift guard. Prefer the cluster centroid; otherwise the latest buffered fix
        // (Doze/idle-timeout paths); null leaves drift undecidable, which never *suppresses* a departure.
        heuristics.stationaryAnchor =
            candidate?.let { it.centroidLatitude to it.centroidLongitude }
                ?: heuristics.lastFixLatLon()
        return listOf(RecordingAction.EnterStationary(candidate, reason, armSigMotion))
    }

    private fun toMoving(reason: String): List<RecordingAction> {
        if (state == RecorderState.MOVING) return emptyList()
        state = RecorderState.MOVING
        quietSinceMs = null
        verifyEntryMs = null
        heuristics.stationaryAnchor = null
        return listOf(RecordingAction.EnterMoving(reason))
    }

    /** Enter the cheap first-look [RecorderState.SENSING_DEPARTURE], freezing the verify-window start. */
    private fun toSensing(reason: String, nowMs: Long): List<RecordingAction> {
        if (isVerifying()) return emptyList()
        state = RecorderState.SENSING_DEPARTURE
        quietSinceMs = null
        verifyEntryMs = nowMs
        return listOf(RecordingAction.BeginVerifying(reason, Constants.SENSING_VERIFY_WINDOW_MS))
    }

    /** Escalate the cheap look to the HIGH_ACCURACY [RecorderState.CONFIRMING_DEPARTURE]. Keeps
     *  [verifyEntryMs] from SENSING entry so the confirm sees the whole post-hint window. */
    private fun toConfirming(reason: String, nowMs: Long): List<RecordingAction> {
        if (state == RecorderState.CONFIRMING_DEPARTURE) return emptyList()
        state = RecorderState.CONFIRMING_DEPARTURE
        quietSinceMs = null
        if (verifyEntryMs == null) verifyEntryMs = nowMs
        return listOf(RecordingAction.BeginVerifying(reason, Constants.CONFIRMING_VERIFY_WINDOW_MS))
    }

    private fun revertToStationary(reason: String): List<RecordingAction> {
        state = RecorderState.STATIONARY
        quietSinceMs = null
        verifyEntryMs = null
        heuristics.stationaryAnchor = heuristics.lastFixLatLon()
        return listOf(RecordingAction.RevertToStationary(reason))
    }

    /**
     * CONFIRMING_DEPARTURE -> MOVING decision from [DepartureEvidence] over the verify window. Confirm
     * on EITHER sustained Doppler (several trusted fixes carrying a real walking-or-faster speed) OR a
     * coherent displacement fallback for no/low-Doppler departures (network fixes): a sustained,
     * progressing, kinematically-plausible trace well clear of the stay. Never one fix / one spike.
     */
    private fun confirmsDeparture(ev: DepartureEvidence): Boolean {
        val radius = heuristics.stationaryNoiseRadius()
        // Sustained Doppler -- but corroborated by real displacement from the frozen anchor, so in-place
        // phantom Doppler on accurate (<=30 m) fixes at a pinned spot can never confirm from nothing.
        if (ev.dopplerMovingCount >= Constants.CONFIRM_MIN_DOPPLER_FIXES &&
            ev.maxDisplacementFromAnchor >= radius
        ) return true
        // Coherent displacement fallback (no/low Doppler, e.g. network fixes): a sustained, progressing,
        // kinematically-plausible trace well clear of the stay.
        return ev.trustedFixCount >= Constants.CONFIRM_MIN_DISPLACEMENT_FIXES &&
                ev.maxDisplacementFromAnchor >= Constants.CONFIRM_DISPLACEMENT_RADIUS_MULTIPLE * radius &&
                ev.netDisplacement >= radius &&
                ev.maxStepSpeedMps <= Constants.CONFIRM_MAX_STEP_SPEED_MPS
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
        return isConfident && (fix.accuracyMeters
            ?: Float.MAX_VALUE) <= Constants.SAMPLE_ACCURACY_GATE_METERS
    }
}
