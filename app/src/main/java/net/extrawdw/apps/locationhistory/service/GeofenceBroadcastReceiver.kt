package net.extrawdw.apps.locationhistory.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Wakes the app when the user leaves the dwell geofence so recording can resume. */
@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: RecordingController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            net.extrawdw.apps.locationhistory.core.AppLog.w(
                "GeofenceReceiver",
                "error ${event.errorCode}"
            )
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        net.extrawdw.apps.locationhistory.core.AppLog.i("GeofenceReceiver", "EXIT")

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                controller.handleGeofenceExit()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "net.extrawdw.apps.locationhistory.GEOFENCE_EVENT"
    }
}
