package net.extrawdw.apps.locationhistory.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Receives Activity Recognition transitions and forwards them to the [RecordingController]. */
@AndroidEntryPoint
class ActivityTransitionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: RecordingController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || !ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val events = result.transitionEvents.map { it.activityType to it.transitionType }
        if (events.isEmpty()) return
        net.extrawdw.apps.locationhistory.core.AppLog.i(
            "ARReceiver",
            "received ${events.size} transition(s)"
        )

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                controller.handleActivityTransitions(events)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "net.extrawdw.apps.locationhistory.ACTIVITY_TRANSITION"
    }
}
