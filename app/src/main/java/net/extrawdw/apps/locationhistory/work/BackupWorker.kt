package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
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
        val backupResult = runJob { backupRepository.runScheduledBackup() }
        val backup = evaluate("backup", backupResult)
        if (shouldNotify) {
            notifyBackupFailure(applicationContext, backupResult)
        }
        val gpx = evaluate("gpx", runJob { backupRepository.runScheduledGpxExport() })
        return if (backup == Outcome.RETRY || gpx == Outcome.RETRY) Result.retry() else Result.success()
    }

    private suspend fun runJob(block: suspend () -> BackupResult): BackupResult = try {
        block()
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        BackupResult.Error(e.message ?: e.javaClass.simpleName)
    }

    private enum class Outcome { OK, RETRY }

    private fun evaluate(job: String, result: BackupResult): Outcome = when (result) {
        is BackupResult.Backed -> {
            AppLog.i(
                TAG,
                "backup ok: wrote=${result.report.partitionsWritten} failed=${result.report.partitionsFailed}"
            )
            if (result.report.partitionsFailed > 0 || result.report.cleanupPending) Outcome.RETRY else Outcome.OK
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

    companion object {
        const val KEY_NOTIFY_FAILURE = "notify_failure"

        private const val TAG = "BackupWorker"
    }
}

/** Called as soon as daily backup finishes, independently of the subsequent GPX job. */
internal fun notifyBackupFailure(ctx: Context, result: BackupResult) {
    val title: String
    val text: String
    when (result) {
        is BackupResult.Backed -> {
            when {
                result.report.partitionsFailed > 0 -> {
                    title = ctx.getString(R.string.backup_notify_incomplete_title)
                    text = ctx.getString(R.string.backup_notify_incomplete_text)
                }
                result.report.cleanupPending -> {
                    title = ctx.getString(R.string.backup_notify_needs_attention_title)
                    text = ctx.getString(R.string.backup_result_cleanup_pending)
                }
                else -> {
                    Notifications.cancelBackupFailure(ctx)
                    return
                }
            }
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
