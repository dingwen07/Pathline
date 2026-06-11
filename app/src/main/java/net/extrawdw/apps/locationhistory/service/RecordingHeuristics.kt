package net.extrawdw.apps.locationhistory.service

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.Geo
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
    private val recentSpeeds = ArrayDeque<Float>()
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

    fun pushSpeed(speed: Float) = synchronized(speedLock) {
        recentSpeeds.addLast(speed)
        while (recentSpeeds.size > SPEED_WINDOW) recentSpeeds.removeFirst()
    }

    /** (mean, max, variance) over the recent speed window; zeros when empty. */
    fun speedStats(): Triple<Float, Float, Float> = synchronized(speedLock) {
        if (recentSpeeds.isEmpty()) return Triple(0f, 0f, 0f)
        val mean = recentSpeeds.average().toFloat()
        val max = recentSpeeds.max()
        val variance = recentSpeeds.map { (it - mean) * (it - mean) }.average().toFloat()
        Triple(mean, max, variance)
    }

    /**
     * True when fixes in the last [RECENT_MOTION_WINDOW_MS] show real movement (GPS speed or a spread
     * beyond the stay radius) — used to reject a premature AR STILL while travelling, e.g. a light-rail
     * ride that AR keeps mislabelling as STILL. Looks at a short recent window (not the full fix buffer)
     * so a genuine stop still flips this false within ~the window once the device settles.
     */
    fun recentlyMoving(): Boolean = synchronized(fixLock) {
        if (recentFixes.isEmpty()) return false
        val cutoff = recentFixes.last().t - RECENT_MOTION_WINDOW_MS
        val recent = recentFixes.filter { it.t >= cutoff }
        if (recent.size < 2) return false
        val maxSpeed = recent.mapNotNull { it.speedMps }.maxOrNull() ?: 0f
        if (maxSpeed >= 1.5f) return true
        val spread = recent.maxOf { a ->
            recent.maxOf { b -> Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon) }
        }
        spread > Constants.STATIONARY_RADIUS_METERS
    }

    /** Returns a visit candidate when recent fixes have settled in one spot long enough, even if
     *  Activity Recognition never reported STILL. */
    fun stationaryClusterCandidate(): VisitCandidate? = synchronized(fixLock) {
        if (recentFixes.size < 3) return null
        val minVisitMs = Constants.MIN_VISIT_DURATION_MS
        val now = recentFixes.last().t
        val window = recentFixes.filter { now - it.t <= minVisitMs }
        if (window.size < 3) return null
        if (now - window.first().t < minVisitMs * 0.8) return null
        val goodAccuracy = window.count {
            (it.accuracyMeters ?: 30f) <= Constants.SAMPLE_ACCURACY_GATE_METERS
        }
        if (goodAccuracy < window.size * 0.7) return null
        val meanSpeed = window.mapNotNull { it.speedMps }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        if (meanSpeed > 0.6) return null
        val cLat = window.sumOf { it.lat } / window.size
        val cLon = window.sumOf { it.lon } / window.size
        val withinRadius = window.all {
            Geo.distanceMeters(cLat, cLon, it.lat, it.lon) <= Constants.STATIONARY_RADIUS_METERS
        }
        val mostlyStationary = window.count { it.stationary } >= window.size * 0.8
        if (!withinRadius || !mostlyStationary) return null
        VisitCandidate(
            startMs = window.first().t,
            endMs = now,
            centroidLatitude = cLat,
            centroidLongitude = cLon,
            sampleCount = window.size,
        )
    }

    /**
     * GPS drift = a near-anchor fix while the phone is **physically still**; a real departure either
     * displaces beyond the (noise-widened) stay radius, carries GPS speed, or shakes the phone. This
     * is the gate on leaving the low-power stationary cadence, so a false positive keeps us
     * undersampling — hence physical movement ([motionVariance]) is weighted heavily: any real walk
     * shakes the device and is never suppressed, even at a noisy-GPS place with no GPS speed.
     */
    fun isDriftAt(
        state: DevicePhysicalState,
        lat: Double,
        lon: Double,
        speedMps: Float,
        motionVariance: Float,
    ): Boolean {
        if (state != DevicePhysicalState.STATIONARY) return false
        val anchor = stationaryAnchor ?: return true
        if (speedMps >= Constants.DRIFT_MOVING_SPEED_MPS) return false
        if (motionVariance >= Constants.DRIFT_MOTION_VARIANCE_CEILING) return false
        val d = Geo.distanceMeters(anchor.first, anchor.second, lat, lon)
        return d < stationaryNoiseRadius()
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
