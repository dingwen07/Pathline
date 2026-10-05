package net.extrawdw.apps.locationhistory.work

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity

internal const val WATCHDOG_SAMPLE_STALE_MS = 15 * 60_000L
internal const val WATCHDOG_SAMPLE_TIMEOUT_MS = 30_000L
private const val MAX_FIX_AGE_MS = 30_000L

internal enum class SampleCaptureResult {
    NOT_ALLOWED, RECENT_SAMPLE, TIMED_OUT, NO_FIX, STALE_FIX, SAVED, RECORDING_RESUMED,
}

// Distinguish a provider returning null from our own request deadline expiring.
private data class SampleResponse(val sample: LocationSampleEntity?)

/**
 * One independent attempt per watchdog tick. The only write is an append to the sample store:
 * no recorder callbacks, movement classification, sensor consumption, or cadence changes.
 * [recordIfStillStale] rechecks freshness atomically with insertion so normal recording wins
 * if it delivers a batch while this request is in flight.
 */
internal suspend fun captureStaleLocation(
    isAllowed: suspend () -> Boolean,
    latestSampleTimeMs: suspend () -> Long?,
    requestSample: suspend () -> LocationSampleEntity?,
    recordIfStillStale: suspend (LocationSampleEntity, Long) -> Boolean,
    nowMs: () -> Long = System::currentTimeMillis,
    timeoutMs: Long = WATCHDOG_SAMPLE_TIMEOUT_MS,
): SampleCaptureResult {
    if (!isAllowed()) return SampleCaptureResult.NOT_ALLOWED
    val startedMs = nowMs()
    val latestMs = latestSampleTimeMs()
    if (latestMs != null && latestMs >= startedMs - WATCHDOG_SAMPLE_STALE_MS) {
        return SampleCaptureResult.RECENT_SAMPLE
    }
    val response = withTimeoutOrNull(timeoutMs) { SampleResponse(requestSample()) }
        ?: return SampleCaptureResult.TIMED_OUT
    val sample = response.sample ?: return SampleCaptureResult.NO_FIX
    currentCoroutineContext().ensureActive()
    // Tracking can be disabled or paused, or permission revoked, during the bounded request.
    if (!isAllowed()) return SampleCaptureResult.NOT_ALLOWED
    // FLP requests no historical cache, but a newly derived fix can have a slightly earlier
    // measurement timestamp. Allow that short acquisition delay, never an old gap-filling fix.
    if (sample.timestampMs < nowMs() - MAX_FIX_AGE_MS) return SampleCaptureResult.STALE_FIX
    return if (recordIfStillStale(sample, nowMs() - WATCHDOG_SAMPLE_STALE_MS)) {
        SampleCaptureResult.SAVED
    } else {
        SampleCaptureResult.RECORDING_RESUMED
    }
}
