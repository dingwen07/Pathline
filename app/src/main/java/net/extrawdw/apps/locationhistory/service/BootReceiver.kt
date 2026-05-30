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

/** Re-arms the Activity Recognition heartbeat and persisted geofences after a reboot. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: RecordingController
    @Inject lateinit var geofenceManager: GeofenceManager
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var workScheduler: WorkScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        net.extrawdw.apps.locationhistory.core.AppLog.i("BootReceiver", "boot completed")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (settingsRepository.settings.first().trackingEnabled) {
                    controller.enableTracking()
                    geofenceManager.restore()
                    workScheduler.schedulePeriodicTimelineMaintenance()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
