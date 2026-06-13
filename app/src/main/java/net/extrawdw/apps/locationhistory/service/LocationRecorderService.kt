package net.extrawdw.apps.locationhistory.service

import android.annotation.SuppressLint
import android.Manifest
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.RecorderState
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile
import javax.inject.Inject

/**
 * Foreground service (type `location`) that owns the Fused location request and delivers batched
 * fixes to [LocationUpdatesReceiver] via a PendingIntent, so the OS can wake the app in efficient
 * bursts without the service holding a wakelock. The controller retunes cadence when stationary.
 */
@AndroidEntryPoint
class LocationRecorderService : LifecycleService() {

    @Inject
    lateinit var controller: RecordingController

    @Inject
    lateinit var serviceController: RecorderServiceController

    @Inject
    lateinit var stepCounterMonitor: net.extrawdw.apps.locationhistory.data.enrich.StepCounterMonitor

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged("available")
        override fun onLost(network: Network) = onNetworkChanged("lost")
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: android.net.NetworkCapabilities,
        ) = onNetworkChanged("capabilities")
    }

    // Doze idle-mode transitions feed the controller's Doze-aware departure logic. The deep-Doze
    // verdict is motion-gated by the platform, so it complements our (verified) significant-motion
    // sensor: enter -> durably stationary (disarm), exit -> likely real motion (verify + wake).
    private var idleReceiverRegistered = false
    private val idleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) return
            val idle = getSystemService(PowerManager::class.java)?.isDeviceIdleMode ?: return
            AppLog.i(TAG, "device idle mode changed: idle=$idle")
            lifecycleScope.launch { controller.handleDeviceIdleModeChanged(idle) }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopUpdatesAndSelf()
                return START_NOT_STICKY
            }

            else -> if (!startRecording(intent)) return START_NOT_STICKY
        }
        // The system delivers updates via PendingIntent; if killed, AR/geofence will restart us.
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(intent: Intent?): Boolean {
        // The OS restarts a START_STICKY foreground service with a null intent (process death, app
        // update) — no extras — so we fall back to the defaults and reconcile from stored fixes below.
        val isServiceRestart = intent == null
        val state = intent?.getStringExtra(EXTRA_STATE)
            ?.let { runCatching { RecorderState.valueOf(it) }.getOrNull() }
            ?: RecorderState.UNKNOWN
        val display = intent?.getStringExtra(EXTRA_DISPLAY)
            ?.let { runCatching { DevicePhysicalState.valueOf(it) }.getOrNull() }
            ?: DevicePhysicalState.UNKNOWN
        val profile = intent?.getStringExtra(EXTRA_PROFILE)
            ?.let { runCatching { PowerProfile.valueOf(it) }.getOrNull() }
            ?: PowerProfile.BALANCED

        // A fresh launch (a restart) starts a new log session; re-tunes don't.
        if (isServiceRestart) AppLog.startSession("service-restart")
        AppLog.i(TAG, "startRecording state=$state display=$display profile=$profile (restart=$isServiceRestart)")

        if (!startForeground(display)) {
            // Also remove the PI location request: it is system-persistent and would otherwise
            // keep delivering fixes to a service that never became foreground.
            stopUpdatesAndSelf()
            return false
        }
        registerNetworkCallback()
        registerIdleReceiver()
        // Session-long batched step-counter listener (idempotent across cadence re-tunes).
        stepCounterMonitor.start()

        if (!hasLocationPermission()) {
            AppLog.w(TAG, "no location permission — stopping")
            stopUpdatesAndSelf()
            return false
        }
        runCatching {
            fusedClient.requestLocationUpdates(
                LocationProfiles.buildRequest(state, profile),
                locationPendingIntent(this),
            )
        }.onSuccess {
            serviceController.markStarted(state, display, profile)
            // A restart defaulted to UNKNOWN; let the controller retune to STATIONARY when recent
            // stored fixes already prove a stay, instead of burning the UNKNOWN cadence for hours.
            if (isServiceRestart) {
                lifecycleScope.launch {
                    runCatching { controller.repairStateAfterServiceRestart() }
                        .onFailure { AppLog.e(TAG, "service-restart repair failed", it) }
                }
            }
        }.onFailure { AppLog.e(TAG, "requestLocationUpdates failed", it) }
        return true
    }

    private fun startForeground(display: DevicePhysicalState): Boolean {
        val notification = Notifications.buildRecordingNotification(this, display)
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.RECORDING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: RuntimeException) {
            // SecurityException (missing permission) or ForegroundServiceStartNotAllowedException
            // (an IllegalStateException subclass on API 31+: FGS start not allowed from the
            // background). Either way the start can't become foreground — bail out cleanly
            // instead of crash-looping on START_STICKY restarts. Mirrors the catch in
            // RecorderServiceController.start().
            val message = e.message ?: e.javaClass.simpleName
            AppLog.w(TAG, "location FGS start denied: $message")
            serviceController.markStopped("FGS denied: $message")
            return false
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(Notifications.RECORDING_NOTIFICATION_ID, notification)
        return true
    }

    private fun stopUpdatesAndSelf() {
        AppLog.i(TAG, "stopUpdatesAndSelf")
        runCatching { fusedClient.removeLocationUpdates(locationPendingIntent(this)) }
        unregisterNetworkCallback()
        unregisterIdleReceiver()
        stepCounterMonitor.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Called only when the user manually removes the app from Recents (a system low-memory kill
     * does NOT invoke this). When the stop-on-task-removed feature is enabled, *pause* recording:
     * stop this foreground service and set a hidden autostart-suppression flag (the "Background
     * recording" preference stays ON), then post the alert. Recording resumes automatically when the
     * app is next opened. When the feature is disabled, the START_STICKY service keeps running.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.w(TAG, "onTaskRemoved — app removed from Recents")
        // Async, not runBlocking: pauseRecordingFromTaskRemoval waits on the controller mutex
        // (held for seconds by an in-flight getCurrentLocation) plus DataStore I/O — blocking the
        // main thread here risks an ANR. The FGS stays alive until stopSelf, so the coroutine
        // always gets to finish.
        lifecycleScope.launch {
            val paused = runCatching { controller.pauseRecordingFromTaskRemoval() }
                .onFailure { AppLog.e(TAG, "pauseRecordingFromTaskRemoval failed", it) }
                .getOrDefault(false)
            if (paused) {
                val service = this@LocationRecorderService
                runCatching { fusedClient.removeLocationUpdates(locationPendingIntent(service)) }
                unregisterNetworkCallback()
                unregisterIdleReceiver()
                stepCounterMonitor.stop()
                Notifications.notifyRecordingStopped(service)
                ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLog.w(TAG, "onDestroy (service stopping)")
        runCatching { fusedClient.removeLocationUpdates(locationPendingIntent(this)) }
        unregisterNetworkCallback()
        unregisterIdleReceiver()
        stepCounterMonitor.stop()
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure { AppLog.w(TAG, "network callback registration failed: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        getSystemService(ConnectivityManager::class.java)?.let { cm ->
            runCatching { cm.unregisterNetworkCallback(networkCallback) }
        }
        networkCallbackRegistered = false
    }

    private fun registerIdleReceiver() {
        if (idleReceiverRegistered) return
        runCatching {
            registerReceiver(
                idleReceiver,
                IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            )
            idleReceiverRegistered = true
        }.onFailure { AppLog.w(TAG, "idle receiver registration failed: ${it.message}") }
    }

    private fun unregisterIdleReceiver() {
        if (!idleReceiverRegistered) return
        runCatching { unregisterReceiver(idleReceiver) }
        idleReceiverRegistered = false
    }

    private fun onNetworkChanged(reason: String) {
        AppLog.i(TAG, "network $reason")
        lifecycleScope.launch { controller.handleNetworkChanged() }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_STOP = "net.extrawdw.apps.locationhistory.STOP_RECORDING"
        const val EXTRA_STATE = "state"
        const val EXTRA_DISPLAY = "display"
        const val EXTRA_PROFILE = "profile"
        private const val TAG = "Service"
        private const val REQUEST_CODE = 4013

        fun locationPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, LocationUpdatesReceiver::class.java)
                .setAction(LocationUpdatesReceiver.ACTION)
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }
    }
}
