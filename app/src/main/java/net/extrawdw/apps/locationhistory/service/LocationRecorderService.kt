package net.extrawdw.apps.locationhistory.service

import android.annotation.SuppressLint
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile

/**
 * Foreground service (type `location`) that runs only while the device is actively moving. It owns
 * the Fused location request and delivers batched fixes to [LocationUpdatesReceiver] via a
 * PendingIntent — so the OS can wake the app in efficient bursts without the service holding a
 * wakelock. When the device becomes stationary the controller stops this service entirely.
 */
@AndroidEntryPoint
class LocationRecorderService : LifecycleService() {

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
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
            else -> startRecording(intent)
        }
        // The system delivers updates via PendingIntent; if killed, AR/geofence will restart us.
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(intent: Intent?) {
        val state = intent?.getStringExtra(EXTRA_STATE)
            ?.let { runCatching { DevicePhysicalState.valueOf(it) }.getOrNull() }
            ?: DevicePhysicalState.UNKNOWN
        val profile = intent?.getStringExtra(EXTRA_PROFILE)
            ?.let { runCatching { PowerProfile.valueOf(it) }.getOrNull() }
            ?: PowerProfile.BALANCED

        // A fresh launch (intent==null after a restart) starts a new log session; re-tunes don't.
        if (intent == null) AppLog.startSession("service-restart")
        AppLog.i(TAG, "startRecording state=$state profile=$profile (restart=${intent == null})")

        startForeground(state)

        if (!hasLocationPermission()) {
            AppLog.w(TAG, "no location permission — stopping")
            stopUpdatesAndSelf()
            return
        }
        runCatching {
            fusedClient.requestLocationUpdates(
                LocationProfiles.buildRequest(state, profile),
                locationPendingIntent(this),
            )
        }.onFailure { AppLog.e(TAG, "requestLocationUpdates failed", it) }
    }

    private fun startForeground(state: DevicePhysicalState) {
        val notification = Notifications.buildRecordingNotification(this, state)
        ServiceCompat.startForeground(
            this,
            Notifications.RECORDING_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    private fun stopUpdatesAndSelf() {
        AppLog.i(TAG, "stopUpdatesAndSelf")
        runCatching { fusedClient.removeLocationUpdates(locationPendingIntent(this)) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        AppLog.w(TAG, "onDestroy (service stopping)")
        runCatching { fusedClient.removeLocationUpdates(locationPendingIntent(this)) }
        super.onDestroy()
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
