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
 * Periodic, charging-gated incremental backup to the user's chosen SAF destination. Re-emits only
 * the partitions the dirty-week triggers flagged since the last run, keeping cloud sync cheap.
 *
 * No-ops cleanly when no destination is configured. A lost SAF grant or unavailable encryption key
 * is logged and retried later (the user resolves it from Settings).
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupRepository: BackupRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = when (val result = backupRepository.runScheduledBackup()) {
        is BackupResult.Backed -> {
            AppLog.i(TAG, "backup ok: wrote=${result.report.partitionsWritten} failed=${result.report.partitionsFailed}")
            if (result.report.partitionsFailed > 0) Result.retry() else Result.success()
        }
        BackupResult.NoDestination -> Result.success()
        BackupResult.NeedsReclaim -> { AppLog.w(TAG, "SAF grant lost; awaiting reclaim"); Result.success() }
        BackupResult.KeyUnavailable -> { AppLog.w(TAG, "backup key unavailable; awaiting password"); Result.success() }
        is BackupResult.Error -> { AppLog.w(TAG, "backup error: ${result.message}"); Result.retry() }
        is BackupResult.Restored -> Result.success()
    }

    private companion object {
        const val TAG = "BackupWorker"
    }
}
