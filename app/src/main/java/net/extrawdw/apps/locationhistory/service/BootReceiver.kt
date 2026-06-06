package net.extrawdw.apps.locationhistory.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject

/** Re-arms recording, Activity Recognition and persisted geofences after system restart events. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: RecordingController
    @Inject
    lateinit var settingsRepository: SettingsRepository
    @Inject
    lateinit var workScheduler: WorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_USER_UNLOCKED
        ) return

        net.extrawdw.apps.locationhistory.core.AppLog.i("BootReceiver", "startup event $action")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (settingsRepository.settings.first().trackingEnabled) {
                    workScheduler.schedulePeriodicTimelineMaintenance()
                    if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                        controller.rearmPassiveSignalsIfPreviouslyEnabled()
                    } else {
                        controller.resumeIfPreviouslyEnabled()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
