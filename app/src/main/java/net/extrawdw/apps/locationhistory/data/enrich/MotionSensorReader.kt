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
import kotlin.math.sqrt

/**
 * Reads a short burst of accelerometer data to compute a motion-energy feature (variance of the
 * linear acceleration magnitude). The sensor is registered only for the sampling window and then
 * immediately unregistered, so it never drains battery while idle — consistent with the app's
 * no-unnecessary-wakelock recording strategy.
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
        var listener: SensorEventListener? = null
        try {
            withTimeoutOrNull(windowMs + 500L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val l = object : SensorEventListener {
                        val startMs = System.currentTimeMillis()
                        override fun onSensorChanged(event: SensorEvent) {
                            val x = event.values[0]
                            val y = event.values[1]
                            val z = event.values[2]
                            // Subtract gravity to approximate linear acceleration energy.
                            magnitudes.add(sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH)
                            if (System.currentTimeMillis() - startMs >= windowMs && cont.isActive) {
                                cont.resume(Unit)
                            }
                        }
                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    }
                    listener = l
                    manager.registerListener(l, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
                    cont.invokeOnCancellation { manager.unregisterListener(l) }
                }
            }
        } finally {
            listener?.let { manager.unregisterListener(it) }
        }
        return variance(magnitudes)
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
}
