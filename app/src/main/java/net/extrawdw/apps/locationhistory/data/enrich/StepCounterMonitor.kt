package net.extrawdw.apps.locationhistory.data.enrich

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventCallback
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maintains one long-lived, hub-batched [Sensor.TYPE_STEP_COUNTER] listener for the recording
 * session and converts its cumulative-since-boot value into per-batch deltas
 * ([stepsSinceLastBatch]) for the `stepDelta` sample column.
 *
 * Battery design (see the step-counter section of docs/recorder-timeline-refactor-plan.md):
 *  - The CDD requires the step counter to count in the sensor hub with the SoC asleep, so the
 *    counting itself is ~free. The only AP cost is event delivery, bounded by
 *    [MAX_REPORT_LATENCY_US]: events coalesce in the hub FIFO and arrive in rare bursts.
 *  - The sensor is non-wakeup, so deliveries defer through Doze instead of breaking it.
 *  - Freshness comes from [SensorManager.flush] issued only while handling a location batch —
 *    the AP is already awake then, so no extra wakeups are ever caused.
 *  - The listener must be *persistent* (registered for the whole session, not per read): the
 *    counter is only specified to advance "while activated", so burst registration could miss
 *    steps between bursts on devices where no other client keeps it active.
 *
 * Lifecycle is tied to the recording foreground service ([start]/[stop] from
 * `LocationRecorderService`), so the listener exists exactly while samples are being produced.
 */
@Singleton
class StepCounterMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val sensorManager: SensorManager? =
        context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    /** Latest cumulative-since-boot reading; -1 = none since registration. */
    @Volatile
    private var latest: Long = -1

    /** Cumulative value consumed by the previous [stepsSinceLastBatch] call; -1 = none yet. */
    @Volatile
    private var baseline: Long = -1

    @Volatile
    private var registered = false

    @Volatile
    private var flushWaiter: CompletableDeferred<Unit>? = null

    private val callback = object : SensorEventCallback() {
        override fun onSensorChanged(event: SensorEvent) {
            latest = event.values[0].toLong()
        }

        override fun onFlushCompleted(sensor: Sensor) {
            flushWaiter?.complete(Unit)
        }
    }

    /** Arm the session listener. Idempotent; a no-op without hardware or the AR permission. */
    fun start() {
        val manager = sensorManager ?: return
        val stepSensor = sensor ?: return
        if (registered) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        latest = -1
        baseline = -1
        registered = runCatching {
            manager.registerListener(
                callback, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US,
            )
        }.getOrDefault(false)
    }

    /** Disarm and forget the session baseline. Idempotent. */
    fun stop() {
        if (!registered) return
        runCatching { sensorManager?.unregisterListener(callback) }
        registered = false
        latest = -1
        baseline = -1
    }

    /**
     * Steps taken since the previous call (one call per delivered location batch; the result is
     * stamped on the batch's last sample, so summing non-null `stepDelta` over a time span yields
     * the steps in that span). Flushes the hub FIFO first so the cached value is current — cheap,
     * because the AP is already awake handling the batch.
     *
     * Null means "unknown", never "zero": no hardware / not registered, no reading delivered yet
     * (some hubs stay silent until the first step after registration), the session's first
     * reading (no covered interval), or a counter reset (reboot) since the last call.
     */
    suspend fun stepsSinceLastBatch(): Int? {
        if (!registered) return null
        flushAndAwait()
        val current = latest
        if (current < 0) return null
        val previous = baseline
        baseline = current
        if (previous < 0) return null
        if (current < previous) return null   // counter reset (reboot / hub restart)
        return (current - previous).toInt()
    }

    private suspend fun flushAndAwait() {
        val manager = sensorManager ?: return
        val waiter = CompletableDeferred<Unit>()
        flushWaiter = waiter
        val requested = runCatching { manager.flush(callback) }.getOrDefault(false)
        if (requested) withTimeoutOrNull(FLUSH_TIMEOUT_MS) { waiter.await() }
        flushWaiter = null
    }

    private companion object {
        /** Hub FIFO coalescing window; deliveries also piggyback any earlier AP wakeup. */
        const val MAX_REPORT_LATENCY_US = 5 * 60_000_000
        const val FLUSH_TIMEOUT_MS = 250L
    }
}
