package net.extrawdw.apps.locationhistory.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.core.AppLog
import javax.inject.Inject

/**
 * Handles the action buttons on the recording-paused alert (see [Notifications]). Lets the user
 * resume recording immediately (lifting the task-removal pause without reopening the app), or turn
 * the stop-on-close feature off entirely (which also resumes recording).
 */
@AndroidEntryPoint
class RecordingActionReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: RecordingController

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_RESUME_RECORDING && action != ACTION_KEEP_RECORDING_ON_CLOSE) return

        AppLog.i(TAG, "notification action $action")
        // Dismiss the alert regardless of which action was tapped.
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(Notifications.RECORDING_STOPPED_NOTIFICATION_ID)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (action == ACTION_KEEP_RECORDING_ON_CLOSE) {
                    controller.disableStopOnCloseAndResume()
                } else {
                    controller.resumeRecordingFromUser()
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "notification action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESUME_RECORDING =
            "net.extrawdw.apps.locationhistory.RESUME_RECORDING"
        const val ACTION_KEEP_RECORDING_ON_CLOSE =
            "net.extrawdw.apps.locationhistory.KEEP_RECORDING_ON_CLOSE"
        private const val TAG = "RecordingAction"
    }
}
