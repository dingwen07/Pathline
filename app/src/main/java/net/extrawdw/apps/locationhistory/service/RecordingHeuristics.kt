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

    /** (lat, lon) of the most recent buffered fix, or null when the buffer is empty — used to anchor
     *  the drift guard when a stationary entry has no cluster centroid (Doze / idle-timeout paths). */
    fun lastFixLatLon(): Pair<Double, Double>? = synchronized(fixLock) {
        recentFixes.lastOrNull()?.let { it.lat to it.lon }
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
     *
     * Spread is *positional* evidence, so only positionally-trustworthy fixes may contribute:
     * indoors, 100-200 m-accuracy fixes scatter far beyond the stay radius while the phone sits
     * still, and counting them kept this true forever — every AR STILL was rejected, the recorder
     * never dropped to the stationary cadence, and idle days recorded at a moving cadence (the
     * June 2026 sample-volume explosion). The threshold is the noise-widened stay radius for the
     * same reason. GPS speed (Doppler) is not position-derived and keeps using every fix.
     */
    fun recentlyMoving(): Boolean = synchronized(fixLock) {
        if (recentFixes.isEmpty()) return false
        val cutoff = recentFixes.last().t - RECENT_MOTION_WINDOW_MS
        val recent = recentFixes.filter { it.t >= cutoff }
        if (recent.size < 2) return false
        val maxSpeed = recent.mapNotNull { it.speedMps }.maxOrNull() ?: 0f
        if (maxSpeed >= 1.5f) return true
        val trusted = recent.filter {
            (it.accuracyMeters ?: 30f) <= Constants.SAMPLE_ACCURACY_GATE_METERS
        }
        if (trusted.size < 2) return false
        val spread = trusted.maxOf { a ->
            trusted.maxOf { b -> Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon) }
        }
        spread > stationaryNoiseRadius()
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
        if (speedMps >= Constants.DRIFT_MOVING_SPEED_MPS) return false
        if (motionVariance >= Constants.DRIFT_MOTION_VARIANCE_CEILING) return false
        val anchor = stationaryAnchor ?: return false
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
