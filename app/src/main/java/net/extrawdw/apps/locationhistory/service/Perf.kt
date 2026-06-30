package net.extrawdw.apps.locationhistory.service

import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace

/**
 * Thin, fail-safe wrapper over Firebase Performance custom traces.
 *
 * Collection is gated globally by [FirebaseTelemetry] (the user's opt-out switch) and by whether
 * Firebase is configured at all. [trace] always runs its block exactly once and simply skips the
 * timing when collection is off or the SDK isn't initialized (a fresh checkout with no
 * google-services.json, or a unit test) -- so callers never branch on telemetry state. Every
 * Firebase call here is guarded, so a telemetry hiccup can never disturb, slow, or fail the work
 * being measured: the block's result and exceptions pass through unchanged.
 *
 * Naming / privacy contract for every trace added through here:
 *  - Trace and metric NAMES must be static and low-cardinality. The dashboard aggregates by name
 *    and a project is capped at ~100 trace + ~100 metric names, so never interpolate a day / week /
 *    id into a name.
 *  - Names, metric keys, and attribute VALUES are uploaded to Firebase. This app keeps timeline
 *    data on-device, so NEVER pass content through them -- no coordinates, place names, addresses,
 *    BSSIDs, or raw timestamps. Counts and bucketed enum-like values only.
 */
object Perf {

    /**
     * Time [block] as a custom trace named [name]; the [Span] handle attaches counts/dimensions.
     * Returns whatever [block] returns; exceptions propagate unchanged (the trace is still stopped).
     */
    suspend fun <T> trace(name: String, block: suspend (Span) -> T): T {
        val raw = runCatching {
            FirebasePerformance.getInstance().newTrace(name).also { it.start() }
        }.getOrNull()
        return try {
            block(Span(raw))
        } finally {
            runCatching { raw?.stop() }
        }
    }

    /** Handle to the running trace. Every method no-ops when collection is disabled. */
    @JvmInline
    value class Span internal constructor(private val trace: Trace?) {
        /** Record a numeric count/size (absolute value). Metric names must be <= 32 chars. */
        fun metric(name: String, value: Long) {
            runCatching { trace?.putMetric(name, value) }
        }

        /** Record a low-cardinality dimension to filter/group by (enum-like values, <= 100 chars). */
        fun attribute(name: String, value: String) {
            runCatching { trace?.putAttribute(name, value) }
        }
    }
}
