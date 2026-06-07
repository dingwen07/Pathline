package net.extrawdw.apps.locationhistory.work

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.data.db.ApiAccessEventEntity
import net.extrawdw.apps.locationhistory.data.repo.ApiAccessRepository
import net.extrawdw.apps.locationhistory.data.repo.ApiScope
import net.extrawdw.apps.locationhistory.service.Notifications
import java.text.NumberFormat
import kotlin.math.max

/**
 * Posts the "an app accessed your Pathline data" alert for a **single** app — a separate, persistent
 * notification per app (distinct id), with the accessing app's icon on the right and Pathline's icon
 * on the left. The text summarizes *what* was read (e.g. "6 visits, 2,636 location samples").
 *
 * Enqueued by [net.extrawdw.apps.locationhistory.api.PathlineProvider] (via
 * [WorkScheduler.enqueueApiAccessNotification]) on each read, as unique work per app with a short
 * initial delay, so a burst of reads coalesces into one run. Repeat alerts for the same app are
 * rate‑limited with an escalating back‑off (1h, 2h, 4h … capped at 24h), reset after [RESET_MS] quiet.
 *
 * Running as system‑managed Work (instead of posting inline from the provider) makes the alert reliable
 * even when the provider call hits a cold, short‑lived Pathline process.
 */
@HiltWorker
class ApiAccessNotifyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: ApiAccessRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val pkg = inputData.getString(KEY_PACKAGE)?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: return Result.success()
        val denied = inputData.getBoolean(KEY_DENIED, false)
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences(ApiAccessRepository.READ_NOTIFY_PREFS, Context.MODE_PRIVATE)

        // Read and denied alerts ride independent back-off lanes (so a denial isn't muffled by a recent
        // read, and vice versa); both are cleared together by the "reset notification timers" action.
        val lane = if (denied) "denied:" else ""
        val last = prefs.getLong("${lane}last:$pkg", 0L)
        var count = prefs.getInt("${lane}count:$pkg", 0)
        // A long quiet spell resets the escalation so the next event notifies promptly.
        if (last != 0L && now - last > RESET_MS) count = 0

        val requiredGap = minOf(BASE_INTERVAL_MS shl count, CAP_INTERVAL_MS)
        if (last != 0L && now - last < requiredGap) return Result.success() // backed off — stay quiet

        val app = runCatching { repo.declaredApps(setOf(pkg)).firstOrNull() }.getOrNull()
        val label = app?.label ?: pkg

        // Summarize this app's activity since the last alert on this lane (or the recent burst window).
        val sinceMs = max(last, now - SUMMARY_WINDOW_MS)
        val recent = runCatching { repo.readsForPackageSince(pkg, sinceMs) }.getOrDefault(emptyList())

        val notificationId: Int
        val title: String
        val text: String
        if (denied) {
            val scopes = deniedSummary(ctx, recent) ?: return Result.success() // nothing denied in window
            notificationId = Notifications.apiAccessDeniedNotificationId(pkg)
            title = ctx.getString(R.string.api_notify_denied_title)
            text = ctx.getString(R.string.api_notify_denied_text_one, label, scopes)
        } else {
            val summary = dataSummary(ctx, recent)
            notificationId = Notifications.apiAccessReadNotificationId(pkg)
            title = ctx.getString(R.string.api_notify_read_title)
            text = if (summary != null) {
                ctx.getString(R.string.api_notify_read_text_one, label, summary)
            } else {
                ctx.getString(R.string.api_notify_read_text_generic, label)
            }
        }

        val largeIcon = app?.iconPng?.let {
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }

        Notifications.notifyApiAccess(ctx, notificationId, title, text, largeIcon)
        prefs.edit {
            putLong("${lane}last:$pkg", now)
            putInt("${lane}count:$pkg", (count + 1).coerceAtMost(MAX_COUNT))
        }
        return Result.success()
    }

    /** Distinct scopes this app was denied in the window, e.g. "Location history". */
    private fun deniedSummary(ctx: Context, events: List<ApiAccessEventEntity>): String? {
        val labels = events.mapNotNull { it.deniedPermission }.distinct().map { permission ->
            ApiScope.entries.firstOrNull { it.permission == permission }
                ?.let { ctx.getString(it.labelRes) } ?: permission
        }
        if (labels.isEmpty()) return null
        return labels.joinToString(ctx.getString(R.string.api_notify_data_separator))
    }

    /** "6 visits, 2,636 location samples" — distinct data types read, with a representative count each. */
    private fun dataSummary(ctx: Context, events: List<ApiAccessEventEntity>): String? {
        // Denied attempts read nothing — summarize only successful reads.
        val reads = events.filter { it.deniedPermission == null }
        if (reads.isEmpty()) return null
        // Use the max row count per type (repeated identical reads shouldn't inflate the number).
        val maxByType = reads.groupBy { it.dataType }.mapValues { (_, e) -> e.maxOf { it.rowCount } }
        val nf = NumberFormat.getInstance()
        val parts = buildList {
            maxByType["visits"]?.let { add(ctx.getString(R.string.api_notify_data_visits, nf.format(it))) }
            maxByType["trips"]?.let { add(ctx.getString(R.string.api_notify_data_trips, nf.format(it))) }
            maxByType["samples"]?.let { add(ctx.getString(R.string.api_notify_data_samples, nf.format(it))) }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(ctx.getString(R.string.api_notify_data_separator))
    }

    companion object {
        const val KEY_PACKAGE = "package"
        const val KEY_DENIED = "denied"

        private const val BASE_INTERVAL_MS = 60L * 60 * 1000        // 1h after the first alert
        private const val CAP_INTERVAL_MS = 24L * 60 * 60 * 1000    // back-off caps at 24h
        private const val RESET_MS = 48L * 60 * 60 * 1000           // quiet this long -> reset escalation
        private const val SUMMARY_WINDOW_MS = 30L * 60 * 1000       // window summarized in the alert
        private const val MAX_COUNT = 6                             // 1h<<6 already exceeds the cap
    }
}
