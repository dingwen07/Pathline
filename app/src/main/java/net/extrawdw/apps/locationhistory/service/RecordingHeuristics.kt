package net.extrawdw.apps.locationhistory.service

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.RecorderState
import net.extrawdw.apps.locationhistory.domain.VisitCandidate
import kotlin.math.sqrt

/** One recent fix as the heuristics see it — decoupled from `android.location.Location` so the
 *  decision logic stays JVM-testable. */
data class RecentFix(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val stationary: Boolean,
)

/**
 * Movement evidence gathered over a departure-verification window, judged against the frozen stay
 * anchor. Produced by [RecordingHeuristics.departureEvidenceSince] and consumed by the
 * [net.extrawdw.apps.locationhistory.core.RecorderState.CONFIRMING_DEPARTURE] confirm rule. All counts
 * are over the accuracy-gated subset (coarse multipath fixes are excluded, never counted as motion).
 */
data class DepartureEvidence(
    /** Trusted (accuracy-gated, in-window) fixes -- the denominator for a credible verdict. */
    val trustedFixCount: Int,
    /** Trusted fixes carrying a valid walking-or-faster Doppler speed. */
    val dopplerMovingCount: Int,
    /** Farthest a trusted fix got from the frozen stay anchor (m); 0 when no anchor. */
    val maxDisplacementFromAnchor: Double,
    /** Half-vs-half net displacement across the trusted window (m) -- progressing vs oscillating. */
    val netDisplacement: Double,
    /** Largest fix-to-fix implied speed (m/s) -- the teleport guard for the no-Doppler fallback. */
    val maxStepSpeedMps: Double,
)

/**
 * The pure decision core of [RecordingController] (backlog #7): the recent-fix and speed buffers
 * and every heuristic computed over them — the drift guard, [recentlyMoving], the
 * stationary-cluster detector and the significant-motion backoff curve. No Android types, no
 * clocks, no I/O: the controller feeds fixes/speeds in and acts on the verdicts, so the rules that
 * historically regressed (movement undersampling, June 2026) are pinned by `RecordingHeuristicsTest`
 * instead of only by field debugging.
 *
 * Thread-safety matches the original inline code: the fix and speed buffers are guarded by their
 * own locks; [stationaryAnchor] is volatile and owned by the controller's state transitions.
 */
internal class RecordingHeuristics {

    private val recentFixes = ArrayDeque<RecentFix>()
    private val fixLock = Any()

    // (timestamp, speed) pairs, pruned by BOTH time ([SPEED_WINDOW_MS]) and count ([SPEED_WINDOW]).
    // Time pruning is essential: a still phone whose fused provider stops reporting Doppler speed
    // (network/WiFi fixes) would otherwise FREEZE this window with whatever was last in it -- one
    // stale speed spike then pinned the classifier to a moving verdict for hours (the 06-13 4h
    // drain). Pruning by the latest fix's time lets the window drain to empty when speeds stop.
    private val recentSpeeds = ArrayDeque<Pair<Long, Float>>()
    private val speedLock = Any()

    /** Centroid of the stay being guarded, or null when not stationary-anchored. */
    @Volatile
    var stationaryAnchor: Pair<Double, Double>? = null

    /** Append one classified fix and trim the buffer to the rolling window. */
    fun pushFix(fix: RecentFix) = synchronized(fixLock) {
        recentFixes.addLast(fix)
        val cutoff = fix.t - FIX_WINDOW_MS
        while (recentFixes.isNotEmpty() && recentFixes.first().t < cutoff) recentFixes.removeFirst()
    }

    /** Replace the buffer wholesale (process-restart repair from stored samples). */
    fun replaceFixes(fixes: List<RecentFix>) = synchronized(fixLock) {
        recentFixes.clear()
        fixes.forEach { recentFixes.addLast(it) }
    }

    /** (lat, lon) of the most recent buffered fix, or null when the buffer is empty — used to anchor
     *  the drift guard when a stationary entry has no cluster centroid (Doze / idle-timeout paths). */
    fun lastFixLatLon(): Pair<Double, Double>? = synchronized(fixLock) {
        recentFixes.lastOrNull()?.let { it.lat to it.lon }
    }

    fun pushSpeed(speed: Float, atMs: Long) = synchronized(speedLock) {
        recentSpeeds.addLast(atMs to speed)
        pruneSpeeds(atMs)
    }

    private fun pruneSpeeds(nowMs: Long) {
        val cutoff = nowMs - SPEED_WINDOW_MS
        while (recentSpeeds.isNotEmpty() && recentSpeeds.first().first < cutoff) recentSpeeds.removeFirst()
        while (recentSpeeds.size > SPEED_WINDOW) recentSpeeds.removeFirst()
    }

    /**
     * (mean, max, variance) over the recent speed window as of [nowMs]; zeros when empty. Prunes by
     * [nowMs] FIRST so speeds that have aged past [SPEED_WINDOW_MS] stop counting even when no new
     * speed has arrived to push them out -- the caller passes every fix's time (speed-bearing or not),
     * so a stalled Doppler stream drains the window instead of freezing a stale moving verdict.
     */
    fun speedStats(nowMs: Long): Triple<Float, Float, Float> = synchronized(speedLock) {
        pruneSpeeds(nowMs)
        if (recentSpeeds.isEmpty()) return Triple(0f, 0f, 0f)
        val speeds = recentSpeeds.map { it.second }
        val mean = speeds.average().toFloat()
        val max = speeds.max()
        val variance = speeds.map { (it - mean) * (it - mean) }.average().toFloat()
        Triple(mean, max, variance)
    }

    /**
     * True when recent fixes show the device actually TRANSLATED across space -- the GPS corroborator
     * that overrides a false AR STILL during a smooth ride (which AR mislabels STILL) and keeps a real
     * trip out of the stationary cadence. GPS speed alone is deliberately NOT trusted: indoor multipath
     * fakes Doppler speed AND scatters position far beyond the stay while the phone sits still (the
     * 06-13 phantom-speed drain, GPS reporting up to 31 m/s with the centroid fixed at home), and raw
     * spread is fooled the same way -- both kept this true forever, pinning the moving cadence on idle
     * days. Net displacement (see [recentNetDisplacementMeters]) cancels that zero-mean scatter, so
     * only genuine progress beyond the noise-widened stay radius counts as movement.
     */
    fun recentlyMoving(): Boolean =
        recentNetDisplacementMeters(RECENT_MOTION_WINDOW_MS) > stationaryNoiseRadius()

    /**
     * MOVING keep-alive: true when a slow real trip is genuinely progressing -- the slow-trip
     * self-demote fix. [recentlyMoving]'s half-centroid net-displacement test over a 90s window has an
     * effective ~1.8 m/s floor (above walking pace), so a slow real trip (creeping traffic, an
     * AR-untagged vehicle) with no steps would self-demote mid-trip after MOVING_IDLE_TIMEOUT_MS. This
     * catches it by requiring BOTH, over the longer [Constants.MOVING_RUN_WINDOW_MS] window:
     *  - sustained Doppler -- a fraction of accuracy-gated ([Constants.DOPPLER_ACCURACY_GATE_M]) fixes
     *    carrying speed >= [Constants.WALK_SPEED_FLOOR_MPS], floored by
     *    [Constants.MOVING_KEEPALIVE_MIN_MOVING_FIXES] so one spike can't qualify; AND
     *  - REAL net translation past the drift floor.
     *
     * The net-translation requirement is the decisive guard against the 06-13 drain class: indoor
     * multipath fakes Doppler on a MOTIONLESS phone (speeds up to 31 m/s with the centroid pinned), but
     * it cannot fake progression -- the half-centroid net cancels its zero-mean scatter. A pinned spot
     * nets ~0 -> keep-alive false -> the recorder self-demotes via the idle-timeout; a real ~1 m/s trip
     * nets ~90 m over 3 min -> keep-alive true. Net is floored at [Constants.DRIFT_DISPLACEMENT_METERS]
     * (not the adaptive radius) so a trip the classifier mislabels STATIONARY can't inflate its own
     * threshold. The window drains by fix time, never freezing on a stale spike.
     */
    fun sustainedDopplerMoving(): Boolean = synchronized(fixLock) {
        if (recentFixes.isEmpty()) return false
        val cutoff = recentFixes.last().t - Constants.MOVING_RUN_WINDOW_MS
        val trusted = recentFixes.filter {
            it.t >= cutoff && it.speedMps != null &&
                    (it.accuracyMeters ?: Float.MAX_VALUE) <= Constants.DOPPLER_ACCURACY_GATE_M
        }
        if (trusted.size < Constants.MIN_MOVING_WINDOW_FIXES) return false
        val moving = trusted.count { (it.speedMps ?: 0f) >= Constants.WALK_SPEED_FLOOR_MPS }
        if (moving < Constants.MOVING_KEEPALIVE_MIN_MOVING_FIXES) return false
        if (moving.toFloat() / trusted.size < Constants.MOVING_KEEPALIVE_FIX_FRACTION) return false
        recentNetDisplacementMeters(Constants.MOVING_RUN_WINDOW_MS) > Constants.DRIFT_DISPLACEMENT_METERS
    }

    /**
     * Evidence gathered since departure verification began ([sinceMs] = verify-entry time), over the
     * accuracy-gated ([Constants.DOPPLER_ACCURACY_GATE_M]) verify-window fixes, judged against the
     * frozen stay [stationaryAnchor]. Drives the [CONFIRMING_DEPARTURE] confirm: sustained Doppler OR a
     * coherent, kinematically-plausible displacement trace -- never one fix, one spike, or IMU energy.
     * Only fixes accurate enough to trust count, so indoor multipath outliers don't fake a departure.
     */
    fun departureEvidenceSince(sinceMs: Long): DepartureEvidence = synchronized(fixLock) {
        val anchor = stationaryAnchor
        val window = recentFixes.filter {
            it.t >= sinceMs &&
                    (it.accuracyMeters ?: Float.MAX_VALUE) <= Constants.DOPPLER_ACCURACY_GATE_M
        }
        if (window.isEmpty()) return DepartureEvidence(0, 0, 0.0, 0.0, 0.0)
        val dopplerMoving = window.count { (it.speedMps ?: 0f) >= Constants.WALK_SPEED_FLOOR_MPS }
        val maxDisp = anchor?.let { a ->
            window.maxOf { Geo.distanceMeters(a.first, a.second, it.lat, it.lon) }
        } ?: 0.0
        val net = if (window.size < 2) 0.0 else {
            val half = maxOf(window.size / 2, 1)
            val a = window.subList(0, half)
            val b = window.subList(half, window.size)
            Geo.distanceMeters(
                a.sumOf { it.lat } / a.size, a.sumOf { it.lon } / a.size,
                b.sumOf { it.lat } / b.size, b.sumOf { it.lon } / b.size,
            )
        }
        var maxStep = 0.0
        for (i in 1 until window.size) {
            val dtSec = (window[i].t - window[i - 1].t) / 1000.0
            if (dtSec > 0) {
                val d = Geo.distanceMeters(
                    window[i - 1].lat, window[i - 1].lon, window[i].lat, window[i].lon,
                )
                maxStep = maxOf(maxStep, d / dtSec)
            }
        }
        DepartureEvidence(window.size, dopplerMoving, maxDisp, net, maxStep)
    }

    /**
     * Net displacement (m) over the last [windowMs]: distance between the centroid of the first half
     * and the centroid of the last half of the positionally-trustworthy fixes in the window. Averaging
     * each half cancels zero-mean GPS multipath scatter (which inflates raw spread to hundreds of
     * metres on a still phone -- the 06-13 drain saw centroid-fixed scatter while GPS reported 31 m/s),
     * so only a real, progressing translation registers. GPS speed is deliberately NOT consulted here:
     * indoor multipath fakes Doppler on a motionless phone, so position progress is the only honest
     * movement evidence. 0 when too few trustworthy fixes to judge -- which reads as "not moving",
     * biasing toward low power (avoiding drain matters more than catching every slow departure).
     */
    fun recentNetDisplacementMeters(windowMs: Long): Double = synchronized(fixLock) {
        if (recentFixes.isEmpty()) return 0.0
        val cutoff = recentFixes.last().t - windowMs
        val trusted = recentFixes.filter {
            it.t >= cutoff && (it.accuracyMeters ?: 30f) <= Constants.SAMPLE_ACCURACY_GATE_METERS
        }
        if (trusted.size < 2) return 0.0
        val half = maxOf(trusted.size / 2, 1)
        val a = trusted.subList(0, half)
        val b = trusted.subList(half, trusted.size)
        val aLat = a.sumOf { it.lat } / a.size
        val aLon = a.sumOf { it.lon } / a.size
        val bLat = b.sumOf { it.lat } / b.size
        val bLon = b.sumOf { it.lon } / b.size
        Geo.distanceMeters(aLat, aLon, bLat, bLon)
    }

    /**
     * Returns a visit candidate when recent fixes have settled in one spot long enough, even if
     * Activity Recognition never reported STILL (AR also stays silent when a session *starts*
     * already-still — the Transition API only reports changes — so this detector is the only
     * stationary entry on a cold start at home).
     *
     * The verdict is judged over the positionally-trustworthy subset of the window: indoors most
     * fixes can be 100-200 m coarse, and letting one such outlier veto the whole window (or
     * demanding that 70% of all fixes be accurate) kept the recorder out of the stationary cadence
     * for entire idle days. Coarse fixes neither qualify nor veto; each trusted fix may wobble up
     * to its own reported accuracy beyond the stay radius before it breaks the cluster.
     */
    fun stationaryClusterCandidate(): VisitCandidate? = synchronized(fixLock) {
        if (recentFixes.size < 3) return null
        val minVisitMs = Constants.MIN_VISIT_DURATION_MS
        val now = recentFixes.last().t
        val window = recentFixes.filter { now - it.t <= minVisitMs }
        if (window.size < 3) return null
        if (now - window.first().t < minVisitMs * 0.8) return null
        val good = window.filter {
            (it.accuracyMeters ?: 30f) <= Constants.SAMPLE_ACCURACY_GATE_METERS
        }
        // The trusted subset must be dense enough to anchor the verdict on its own: at least 3
        // fixes spanning most of the window (not 3 clumped at one end).
        if (good.size < 3) return null
        if (good.last().t - good.first().t < minVisitMs * 0.6) return null
        val meanSpeed = good.mapNotNull { it.speedMps }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        if (meanSpeed > 0.6) return null
        val cLat = good.sumOf { it.lat } / good.size
        val cLon = good.sumOf { it.lon } / good.size
        val withinRadius = good.all {
            Geo.distanceMeters(cLat, cLon, it.lat, it.lon) <=
                    maxOf(Constants.STATIONARY_RADIUS_METERS, (it.accuracyMeters ?: 30f).toDouble())
        }
        val mostlyStationary = good.count { it.stationary } >= good.size * 0.8
        if (!withinRadius || !mostlyStationary) return null
        VisitCandidate(
            startMs = good.first().t,
            endMs = now,
            centroidLatitude = cLat,
            centroidLongitude = cLon,
            sampleCount = good.size,
        )
    }

    /**
     * GPS drift = a near-anchor fix while the phone is **physically still**; a real departure either
     * displaces beyond the (noise-widened) stay radius, carries GPS speed, or shakes the phone. This
     * is the gate on leaving the low-power stationary cadence, so a false positive keeps us
     * undersampling — hence physical movement ([motionVariance]) is weighted heavily: any real walk
     * shakes the device and is never suppressed, even at a noisy-GPS place with no GPS speed.
     * Without a [stationaryAnchor] there is no displacement to measure, so nothing can be called
     * drift — treating that as drift would suppress every departure until an anchor appears.
     */
    fun isDriftAt(
        state: RecorderState,
        lat: Double,
        lon: Double,
        speedMps: Float,
        motionVariance: Float,
    ): Boolean {
        if (state != RecorderState.STATIONARY) return false
        return isWithinStay(lat, lon, speedMps, motionVariance)
    }

    /**
     * Shared geometry behind the drift guard ([isDriftAt]) and the departure-verification check: is a
     * fix consistent with *still being at the current stay*? — near the anchor, no GPS speed, and the
     * phone physically still. A fix that is NOT within the stay has left it (displaced beyond the
     * noise radius, carries speed, or shakes the device). Without a [stationaryAnchor] there is no
     * stay to be within, so this is false (a departure is never suppressed for lack of an anchor).
     */
    fun isWithinStay(lat: Double, lon: Double, speedMps: Float, motionVariance: Float): Boolean {
        // A physically shaking phone has really left -- IMU energy can't be faked by GPS multipath.
        if (motionVariance >= Constants.DRIFT_MOTION_VARIANCE_CEILING) return false
        val anchor = stationaryAnchor ?: return false
        val d = Geo.distanceMeters(anchor.first, anchor.second, lat, lon)
        // Positionally outside the (noise-widened) stay -> left.
        if (d >= stationaryNoiseRadius()) return false
        // Near the anchor with a quiet IMU: still here even when GPS reports speed. Bare Doppler is
        // phantom indoors, so only believe a speed-driven departure when the position is ACTUALLY
        // progressing (net displacement), not oscillating in place (the 06-13 phantom-speed exits).
        if (speedMps >= Constants.DRIFT_MOVING_SPEED_MPS &&
            recentNetDisplacementMeters(RECENT_MOTION_WINDOW_MS) >= stationaryNoiseRadius()
        ) return false
        return true
    }

    /**
     * True when a fix is displaced from the stay anchor beyond BOTH the noise radius AND its own
     * reported accuracy — a real, large move that even a coarse (BALANCED) fix can attest to, without
     * trusting a noisy fix's small wobble at face value. Used by SENSING_DEPARTURE to decide whether to
     * escalate the cheap look to the HIGH_ACCURACY confirm. A null-accuracy fix can't attest (returns
     * false); without an anchor there is nothing to be displaced from (false). Mirrors the
     * accuracy-aware tolerance the cluster detector already uses (max(radius, per-fix accuracy)).
     */
    fun hasLeftStayBeyondAccuracy(lat: Double, lon: Double, accuracyMeters: Float?): Boolean {
        val anchor = stationaryAnchor ?: return false
        val d = Geo.distanceMeters(anchor.first, anchor.second, lat, lon)
        return d >= maxOf(stationaryNoiseRadius(), (accuracyMeters ?: Float.MAX_VALUE).toDouble())
    }

    /**
     * Radius (m) within which a near-anchor fix counts as jitter, widened to the GPS noise actually
     * observed here (RMS spread of recent stationary fixes) so a noisy place tolerates more wobble —
     * floored at [Constants.DRIFT_DISPLACEMENT_METERS] and capped at [Constants.PLACE_MAX_RADIUS_METERS]
     * so a pathological place can't open a huge dead zone. Only stationary-flagged fixes are used, so a
     * departure trajectory never inflates it.
     */
    fun stationaryNoiseRadius(): Double = synchronized(fixLock) {
        val base = Constants.DRIFT_DISPLACEMENT_METERS
        val cap = Constants.PLACE_MAX_RADIUS_METERS
        val stat = recentFixes.filter { it.stationary }
        if (stat.size < 3) return base
        val cLat = stat.sumOf { it.lat } / stat.size
        val cLon = stat.sumOf { it.lon } / stat.size
        val rms = sqrt(
            stat.sumOf {
                val d = Geo.distanceMeters(cLat, cLon, it.lat, it.lon)
                d * d
            } / stat.size,
        )
        (rms * NOISE_RMS_FACTOR).coerceIn(base, cap)
    }

    companion object {
        const val SPEED_WINDOW = 10
        const val SPEED_WINDOW_MS =
            90_000L  // time bound on the speed window (drains when Doppler stalls)
        const val FIX_WINDOW_MS = 6 * 60_000L
        const val RECENT_MOTION_WINDOW_MS = 90_000L
        const val NOISE_RMS_FACTOR = 2.5    // stationary-fix RMS spread -> drift radius

        // Significant-motion re-arm backoff: base << streak, capped. 30s,1m,2m,4m,8m,16m,30m(cap).
        const val SIG_MOTION_BASE_BACKOFF_MS = 30_000L
        const val SIG_MOTION_MAX_BACKOFF_SHIFTS = 6
        const val SIG_MOTION_MAX_BACKOFF_MS = 30 * 60_000L

        /** Re-arm delay after the [streak]-th consecutive unconfirmed significant-motion trigger. */
        fun sigMotionBackoffMs(streak: Int): Long =
            (SIG_MOTION_BASE_BACKOFF_MS shl streak.coerceAtMost(SIG_MOTION_MAX_BACKOFF_SHIFTS))
                .coerceAtMost(SIG_MOTION_MAX_BACKOFF_MS)
    }
}
