package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.data.repo.BackupRepository
import net.extrawdw.apps.locationhistory.data.repo.BackupResult

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
        val backup = evaluate("backup", backupRepository.runScheduledBackup())
        val gpx = evaluate("gpx", backupRepository.runScheduledGpxExport())
        return if (backup == Outcome.RETRY || gpx == Outcome.RETRY) Result.retry() else Result.success()
    }

    private enum class Outcome { OK, RETRY }

    private fun evaluate(job: String, result: BackupResult): Outcome = when (result) {
        is BackupResult.Backed -> {
            AppLog.i(TAG, "backup ok: wrote=${result.report.partitionsWritten} failed=${result.report.partitionsFailed}")
            if (result.report.partitionsFailed > 0) Outcome.RETRY else Outcome.OK
        }
        is BackupResult.Exported -> { AppLog.i(TAG, "gpx ok: wrote=${result.count} file(s)"); Outcome.OK }
        BackupResult.NoDestination -> Outcome.OK
        BackupResult.NeedsReclaim -> { AppLog.w(TAG, "$job: SAF grant lost; awaiting reclaim"); Outcome.OK }
        BackupResult.KeyUnavailable -> { AppLog.w(TAG, "$job: key unavailable; awaiting password"); Outcome.OK }
        is BackupResult.Error -> { AppLog.w(TAG, "$job error: ${result.message}"); Outcome.RETRY }
        is BackupResult.Restored -> Outcome.OK
    }

    private companion object {
        const val TAG = "BackupWorker"
    }
}
