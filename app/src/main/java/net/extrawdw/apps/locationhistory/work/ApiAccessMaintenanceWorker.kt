package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.data.repo.ApiAccessRepository
import net.extrawdw.apps.locationhistory.service.Notifications

/**
 * Periodic upkeep for the third-party data API (daily, see [WorkScheduler.schedulePeriodicApiAccessCheck]):
 *  1. prunes audit-log rows older than [KEEP_MS]; and
 *  2. on a throttled cadence ([REMINDER_INTERVAL_MS]), reminds the user which apps still hold access.
 *
 * The "an app just read your data" alert is handled separately and promptly by [ApiAccessNotifyWorker];
 * this worker is only background maintenance + the standing-access reminder.
 */
@HiltWorker
class ApiAccessMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: ApiAccessRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val now = System.currentTimeMillis()
        runCatching { repo.prune(KEEP_MS, now) }

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastReminder = prefs.getLong(KEY_LAST_REMINDER, 0L)
        if (now - lastReminder >= REMINDER_INTERVAL_MS) {
            val holders = runCatching { repo.declaredApps(repo.loggedPackages()) }
                .getOrDefault(emptyList())
                .filter { it.granted.isNotEmpty() }
            if (holders.isNotEmpty()) {
                val title = ctx.getString(R.string.api_notify_hold_title)
                val text = if (holders.size == 1) {
                    ctx.getString(R.string.api_notify_hold_text_one, holders[0].label)
                } else {
                    ctx.getString(R.string.api_notify_hold_text_many, holders[0].label, holders.size - 1)
                }
                Notifications.notifyApiAccess(ctx, Notifications.API_ACCESS_HOLD_NOTIFICATION_ID, title, text)
                prefs.edit { putLong(KEY_LAST_REMINDER, now) }
            }
        }
        return Result.success()
    }

    private companion object {
        const val PREFS = "api_access_maintenance"
        const val KEY_LAST_REMINDER = "last_reminder"
        const val KEEP_MS = 90L * 24 * 60 * 60 * 1000
        const val REMINDER_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
