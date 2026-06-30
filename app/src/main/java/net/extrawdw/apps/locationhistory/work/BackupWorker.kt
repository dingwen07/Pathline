package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.data.repo.BackupRepository
import net.extrawdw.apps.locationhistory.data.repo.BackupResult
import net.extrawdw.apps.locationhistory.service.Notifications

/**
 * The shared periodic, charging-gated sync worker. Runs two independent jobs, each a clean no-op
 * when its destination isn't configured:
 *  - the incremental encrypted backup (re-emits only dirty-week partitions), and
 *  - the open-format GPX auto-export (re-exports only weeks changed since the last run).
 *
 * Either can be enabled without the other. A lost SAF grant or unavailable encryption key is logged
 * and retried later (the user resolves it from Settings). The run is retried if either job errors.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupRepository: BackupRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val shouldNotify = inputData.getBoolean(KEY_NOTIFY_FAILURE, true)
        val backupResult = backupRepository.runScheduledBackup()
        val backup = evaluate("backup", backupResult)
        val gpx = evaluate("gpx", backupRepository.runScheduledGpxExport())
        if (shouldNotify) {
            notifyBackupFailure(backupResult)
        }
        return if (backup == Outcome.RETRY || gpx == Outcome.RETRY) Result.retry() else Result.success()
    }

    private enum class Outcome { OK, RETRY }

    private fun evaluate(job: String, result: BackupResult): Outcome = when (result) {
        is BackupResult.Backed -> {
            AppLog.i(
                TAG,
                "backup ok: wrote=${result.report.partitionsWritten} failed=${result.report.partitionsFailed}"
            )
            if (result.report.partitionsFailed > 0) Outcome.RETRY else Outcome.OK
        }

        is BackupResult.Exported -> {
            AppLog.i(TAG, "gpx ok: wrote=${result.count} file(s)"); Outcome.OK
        }

        BackupResult.NoDestination -> Outcome.OK
        BackupResult.NeedsReclaim -> {
            AppLog.w(TAG, "$job: SAF grant lost; awaiting reclaim"); Outcome.OK
        }

        BackupResult.KeyUnavailable -> {
            AppLog.w(TAG, "$job: key unavailable; awaiting password"); Outcome.OK
        }

        is BackupResult.Error -> {
            AppLog.w(TAG, "$job error: ${result.message}"); Outcome.RETRY
        }

        is BackupResult.Restored -> Outcome.OK
    }

    private fun notifyBackupFailure(result: BackupResult) {
        val ctx = applicationContext
        val title: String
        val text: String
        when (result) {
            is BackupResult.Backed -> {
                if (result.report.partitionsFailed <= 0) {
                    Notifications.cancelBackupFailure(ctx)
                    return
                }
                title = ctx.getString(R.string.backup_notify_incomplete_title)
                text = ctx.getString(R.string.backup_notify_incomplete_text)
            }

            BackupResult.NeedsReclaim -> {
                title = ctx.getString(R.string.backup_notify_needs_attention_title)
                text = ctx.getString(R.string.backup_notify_needs_reclaim_text)
            }

            BackupResult.KeyUnavailable -> {
                title = ctx.getString(R.string.backup_notify_needs_attention_title)
                text = ctx.getString(R.string.backup_notify_key_unavailable_text)
            }

            is BackupResult.Error -> {
                title = ctx.getString(R.string.backup_notify_failed_title)
                text = ctx.getString(R.string.backup_notify_failed_text, result.message)
            }

            BackupResult.NoDestination,
            is BackupResult.Exported,
            is BackupResult.Restored -> {
                Notifications.cancelBackupFailure(ctx)
                return
            }
        }
        Notifications.notifyBackupFailure(ctx, title, text)
    }

    companion object {
        const val KEY_NOTIFY_FAILURE = "notify_failure"

        private const val TAG = "BackupWorker"
    }
}
