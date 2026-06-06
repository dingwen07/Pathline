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
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
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
        val state = intent?.getStringExtra(EXTRA_STATE)
            ?.let { runCatching { DevicePhysicalState.valueOf(it) }.getOrNull() }
            ?: DevicePhysicalState.UNKNOWN
        val profile = intent?.getStringExtra(EXTRA_PROFILE)
            ?.let { runCatching { PowerProfile.valueOf(it) }.getOrNull() }
            ?: PowerProfile.BALANCED

        // A fresh launch (intent==null after a restart) starts a new log session; re-tunes don't.
        if (intent == null) AppLog.startSession("service-restart")
        AppLog.i(TAG, "startRecording state=$state profile=$profile (restart=${intent == null})")

        if (!startForeground(state)) {
            stopSelf()
            return false
        }
        registerNetworkCallback()
        registerIdleReceiver()

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
            serviceController.markStarted(state, profile)
        }.onFailure { AppLog.e(TAG, "requestLocationUpdates failed", it) }
        return true
    }

    private fun startForeground(state: DevicePhysicalState): Boolean {
        val notification = Notifications.buildRecordingNotification(this, state)
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.RECORDING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: SecurityException) {
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
        val paused = runCatching { runBlocking { controller.pauseRecordingFromTaskRemoval() } }
            .onFailure { AppLog.e(TAG, "pauseRecordingFromTaskRemoval failed", it) }
            .getOrDefault(false)
        if (paused) {
            runCatching { fusedClient.removeLocationUpdates(locationPendingIntent(this)) }
            unregisterNetworkCallback()
            unregisterIdleReceiver()
            Notifications.notifyRecordingStopped(this)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLog.w(TAG, "onDestroy (service stopping)")
        runCatching { fusedClient.removeLocationUpdates(locationPendingIntent(this)) }
        unregisterNetworkCallback()
        unregisterIdleReceiver()
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
