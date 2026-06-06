package net.extrawdw.apps.locationhistory.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.LocationResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Receives batched Fused location deliveries (via PendingIntent) and hands them to the controller. */
@AndroidEntryPoint
class LocationUpdatesReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: RecordingController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val result = LocationResult.extractResult(intent) ?: return
        val locations = result.locations
        if (locations.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                controller.handleLocations(locations)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "net.extrawdw.apps.locationhistory.LOCATION_UPDATE"
    }
}
