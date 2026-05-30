package net.extrawdw.apps.locationhistory.service

import android.annotation.SuppressLint
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.data.db.GeofenceDao
import net.extrawdw.apps.locationhistory.data.db.GeofenceEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the single "dwell" geofence dropped when the device becomes stationary. While parked, GPS
 * is off and the system watches the geofence for us; leaving it (EXIT) wakes the app to resume
 * recording. This is the core of the power strategy — no GPS and no wakelock while stationary.
 *
 * Geofences are mirrored to the DB so they can be re-registered after a reboot.
 */
@Singleton
class GeofenceManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val geofenceDao: GeofenceDao,
) {
    private val client = LocationServices.getGeofencingClient(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Replace any existing dwell geofence with one centred on the stationary location. */
    @SuppressLint("MissingPermission")
    suspend fun armDwellGeofence(latitude: Double, longitude: Double) {
        if (!hasPermission()) return
        clearAll()
        val geofence = Geofence.Builder()
            .setRequestId(DWELL_ID)
            .setCircularRegion(latitude, longitude, Constants.DWELL_GEOFENCE_RADIUS_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        runCatching {
            client.addGeofences(request, pendingIntent()).await()
            geofenceDao.insert(
                GeofenceEntity(
                    requestId = DWELL_ID,
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = Constants.DWELL_GEOFENCE_RADIUS_METERS,
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun clearAll() {
        runCatching { client.removeGeofences(pendingIntent()).await() }
        geofenceDao.clear()
    }

    /** Re-register persisted geofences after a reboot. */
    @SuppressLint("MissingPermission")
    suspend fun restore() {
        if (!hasPermission()) return
        val stored = geofenceDao.all()
        if (stored.isEmpty()) return
        val geofences = stored.map {
            Geofence.Builder()
                .setRequestId(it.requestId)
                .setCircularRegion(it.latitude, it.longitude, it.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(geofences).build()
        runCatching { client.addGeofences(request, pendingIntent()).await() }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
            .setAction(GeofenceBroadcastReceiver.ACTION)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private companion object {
        const val DWELL_ID = "pathline_dwell"
        const val REQUEST_CODE = 4012
    }
}
