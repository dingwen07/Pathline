package net.extrawdw.apps.locationhistory.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.core.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the hardware **Significant Motion** trigger sensor (`TYPE_SIGNIFICANT_MOTION`) — a one-shot,
 * very low-power wakeup sensor the platform tunes to fire only on motion that likely changes the
 * user's *location* (walking, biking, riding), not in-place phone handling. Armed while the device
 * is stationary, it gives a fast, Doze-surviving "the user has started moving" signal that
 * complements the (laggy) dwell geofence, so a departure is caught in seconds instead of after the
 * geofence finally reports its EXIT.
 *
 * Unlike AR transitions / geofences (PendingIntent-backed, survive process death), a
 * [TriggerEventListener] only fires while the process is alive — which it is whenever recording,
 * since the foreground service stays up the whole tracking period. After firing it auto-unregisters
 * (one-shot); [RecordingController] re-arms it on the next `becameStationary`. Devices without the
 * sensor degrade gracefully: [arm] is a no-op and the geofence remains the departure signal.
 */
@Singleton
class SignificantMotionManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var listener: TriggerEventListener? = null

    fun isAvailable(): Boolean = sensor != null

    /** Arm the one-shot trigger; [onMotion] runs once, off the sensor thread, when motion is detected. */
    fun arm(onMotion: suspend () -> Unit) {
        val s = sensor ?: return
        val manager = sensorManager ?: return
        disarm()
        val l = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                // One-shot: the platform auto-unregisters the listener after it fires.
                listener = null
                AppLog.i(TAG, "significant motion triggered")
                scope.launch { onMotion() }
            }
        }
        listener = l
        manager.requestTriggerSensor(l, s)
    }

    fun disarm() {
        val l = listener ?: return
        val s = sensor ?: return
        sensorManager?.cancelTriggerSensor(l, s)
        listener = null
    }

    private companion object {
        const val TAG = "SignificantMotion"
    }
}
