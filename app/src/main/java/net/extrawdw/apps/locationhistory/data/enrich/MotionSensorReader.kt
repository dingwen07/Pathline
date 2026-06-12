package net.extrawdw.apps.locationhistory.data.enrich

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Summary of one short IMU + barometer burst, recorded as evidence on every sample of the
 * location batch it was collected for (one burst per delivered batch — the values are
 * batch-granular, not per-fix).
 *
 * These are *evidence channels* for the span-level timeline classifier (see
 * docs/recorder-timeline-refactor-plan.md): cadence separates foot modes when GPS speed is
 * useless indoors, gravity churn separates a mounted/resting phone from one carried on-body,
 * and pressure deltas expose elevation change (hiking, flights).
 */
data class MotionBurst(
    /** Variance of the gravity-removed acceleration magnitude — the motion-energy feature. */
    val accelVariance: Float,
    /** Dominant step frequency (Hz) from mean-crossing analysis, or null when no periodic
     *  motion was detected (too few crossings or near-zero energy). */
    val stepCadenceHz: Float?,
    /** Mean angular deviation (degrees) of the gravity direction from its window mean — ~0 for a
     *  mounted/resting phone, large for one bouncing in a pocket. Null without a gravity sensor. */
    val gravityAngleDeltaDeg: Float?,
    /** Barometric pressure (hPa), or null without a barometer. */
    val pressureHpa: Float?,
)

/**
 * Reads short bursts of motion-sensor data. Sensors are registered only for the sampling window
 * and then immediately unregistered, so they never drain battery while idle — consistent with the
 * app's no-unnecessary-wakelock recording strategy.
 *
 * Two entry points: [motionVariance] is the cheap accelerometer-only read used at *decision*
 * moments (drift guard, Doze-exit verification); [burstSummary] is the richer per-batch read that
 * also captures step cadence, gravity stability and pressure as stored evidence.
 */
@Singleton
class MotionSensorReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val sensorManager: SensorManager? =
        context.getSystemService(SensorManager::class.java)

    /**
     * Samples the accelerometer for [windowMs] and returns the variance of the gravity-removed
     * acceleration magnitude. Returns 0f if no sensor or no samples were collected.
     */
    suspend fun motionVariance(windowMs: Long = 1_500L): Float {
        val manager = sensorManager ?: return 0f
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return 0f
        val magnitudes = ArrayList<Float>(64)
        collect(
            manager, windowMs,
            mapOf(accelerometer to { event: SensorEvent ->
                magnitudes.add(linearMagnitude(event))
            }),
        )
        return variance(magnitudes)
    }

    /**
     * Samples accelerometer + gravity + barometer for [windowMs] and summarizes the burst. The
     * accelerometer runs at game rate so step periodicity (1.5-3 Hz) is resolvable; the window is
     * long enough for ~4-7 steps at walking cadence.
     */
    suspend fun burstSummary(windowMs: Long = 2_500L): MotionBurst {
        val manager = sensorManager
            ?: return MotionBurst(0f, null, null, null)
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val barometer = manager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        val magnitudes = ArrayList<Float>(256)
        val magnitudeTimesNs = ArrayList<Long>(256)
        val gravityVectors = ArrayList<FloatArray>(256)
        val pressures = ArrayList<Float>(32)

        val handlers = buildMap<Sensor, (SensorEvent) -> Unit> {
            accelerometer?.let {
                put(it) { event ->
                    magnitudes.add(linearMagnitude(event))
                    magnitudeTimesNs.add(event.timestamp)
                }
            }
            gravity?.let {
                put(it) { event ->
                    gravityVectors.add(
                        floatArrayOf(event.values[0], event.values[1], event.values[2])
                    )
                }
            }
            barometer?.let {
                put(it) { event -> pressures.add(event.values[0]) }
            }
        }
        if (handlers.isEmpty()) return MotionBurst(0f, null, null, null)
        collect(manager, windowMs, handlers, rateUs = SENSOR_BURST_RATE_US)

        return MotionBurst(
            accelVariance = variance(magnitudes),
            stepCadenceHz = stepCadenceHz(magnitudes, magnitudeTimesNs),
            gravityAngleDeltaDeg = gravityAngleDeltaDeg(gravityVectors),
            pressureHpa = pressures.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
        )
    }

    /** Register every (sensor -> handler) pair for [windowMs], then unregister. */
    private suspend fun collect(
        manager: SensorManager,
        windowMs: Long,
        handlers: Map<Sensor, (SensorEvent) -> Unit>,
        rateUs: Int = SensorManager.SENSOR_DELAY_NORMAL,
    ) {
        val listeners = ArrayList<SensorEventListener>(handlers.size)
        try {
            withTimeoutOrNull(windowMs + 500L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val startMs = System.currentTimeMillis()
                    for ((sensor, handler) in handlers) {
                        val l = object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent) {
                                handler(event)
                                if (System.currentTimeMillis() - startMs >= windowMs && cont.isActive) {
                                    cont.resume(Unit)
                                }
                            }

                            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                        }
                        listeners.add(l)
                        manager.registerListener(l, sensor, rateUs)
                    }
                    cont.invokeOnCancellation {
                        listeners.forEach { manager.unregisterListener(it) }
                    }
                }
            }
        } finally {
            listeners.forEach { manager.unregisterListener(it) }
        }
    }

    private fun linearMagnitude(event: SensorEvent): Float {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // Subtract gravity to approximate linear acceleration energy.
        return sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
    }

    private fun variance(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        var sumSq = 0f
        for (v in values) {
            val d = v - mean
            sumSq += d * d
        }
        return sumSq / values.size
    }

    /**
     * Step cadence from hysteresis-gated mean upcrossings of the acceleration magnitude: each
     * stride produces one oscillation, so upcrossings/second ~ steps/second. The hysteresis band
     * rejects sensor noise on a resting phone; null when the signal has too few crossings to call
     * periodic, or the result falls outside the humanly plausible 0.5-4 Hz band.
     */
    private fun stepCadenceHz(magnitudes: List<Float>, timesNs: List<Long>): Float? {
        if (magnitudes.size < 8 || timesNs.size != magnitudes.size) return null
        val durationS = (timesNs.last() - timesNs.first()) / 1e9f
        if (durationS < 1f) return null
        val mean = magnitudes.average().toFloat()
        var crossings = 0
        var below = magnitudes.first() < mean - STEP_HYSTERESIS_MS2
        for (m in magnitudes) {
            if (below && m > mean + STEP_HYSTERESIS_MS2) {
                crossings++
                below = false
            } else if (!below && m < mean - STEP_HYSTERESIS_MS2) {
                below = true
            }
        }
        if (crossings < 3) return null
        val hz = crossings / durationS
        return hz.takeIf { it in 0.5f..4f }
    }

    /** Mean angular deviation (deg) of gravity vectors from their window-mean direction. */
    private fun gravityAngleDeltaDeg(vectors: List<FloatArray>): Float? {
        if (vectors.size < 3) return null
        val mean = FloatArray(3)
        for (v in vectors) {
            mean[0] += v[0]; mean[1] += v[1]; mean[2] += v[2]
        }
        val meanNorm = norm(mean)
        if (meanNorm < 1e-3f) return null
        var sumDeg = 0.0
        var counted = 0
        for (v in vectors) {
            val n = norm(v)
            if (n < 1e-3f) continue
            val cos = ((v[0] * mean[0] + v[1] * mean[1] + v[2] * mean[2]) / (n * meanNorm))
                .coerceIn(-1f, 1f)
            sumDeg += Math.toDegrees(acos(cos.toDouble()))
            counted++
        }
        if (counted == 0) return null
        return (sumDeg / counted).toFloat()
    }

    private fun norm(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private companion object {
        /** ~50 Hz — resolves step periodicity without the cost of SENSOR_DELAY_FASTEST. */
        const val SENSOR_BURST_RATE_US = 20_000

        /** Hysteresis half-band (m/s^2) around the mean for step-crossing detection. */
        const val STEP_HYSTERESIS_MS2 = 0.4f
    }
}
