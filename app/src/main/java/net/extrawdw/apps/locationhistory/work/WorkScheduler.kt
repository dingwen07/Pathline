package net.extrawdw.apps.locationhistory.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.repo.TrainingRepository
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Central place that enqueues all deferred background work with the right constraints. */
@Singleton
class WorkScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val trainingRepository: TrainingRepository,
) {

    fun enqueueTimelineMaintenance(dayEpoch: Long, reason: String): UUID {
        val request = OneTimeWorkRequestBuilder<TimelineMaintenanceWorker>()
            .setInputData(
                workDataOf(
                    TimelineMaintenanceWorker.KEY_DAY to dayEpoch,
                    TimelineMaintenanceWorker.KEY_REASON to reason,
                ),
            )
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(
            timelineMaintenanceWorkName(dayEpoch),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }

    /** Expedited path for user-visible refreshes and app-open reconciliation. */
    fun enqueueTimelineMaintenanceNow(
        dayEpoch: Long = TimeBuckets.dayEpoch(System.currentTimeMillis()),
        reason: String,
    ): UUID {
        val request = OneTimeWorkRequestBuilder<TimelineMaintenanceWorker>()
            .setInputData(
                workDataOf(
                    TimelineMaintenanceWorker.KEY_DAY to dayEpoch,
                    TimelineMaintenanceWorker.KEY_REASON to reason,
                ),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(
            timelineMaintenanceNowWorkName(dayEpoch),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }

    /** Periodic catch-up for samples delivered while the UI is closed. */
    fun schedulePeriodicTimelineMaintenance() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<TimelineMaintenanceWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf(TimelineMaintenanceWorker.KEY_REASON to "periodic"))
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_TIMELINE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Periodic liveness watchdog for the recorder. Runs at the WorkManager minimum interval and, when
     * tracking is on but the foreground service isn't running, restarts it (or alerts the user if a
     * background start is refused). Deliberately *unconstrained* — battery-low and idle are exactly
     * when an aggressive system kills the recorder, so the check must still run. It self-gates on the
     * tracking preference, so a stray tick after the user turns recording off is a cheap no-op.
     */
    fun scheduleRecordingWatchdog() {
        val request = PeriodicWorkRequestBuilder<RecordingWatchdogWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_RECORDING_WATCHDOG,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelRecordingWatchdog() {
        workManager.cancelUniqueWork(WORK_RECORDING_WATCHDOG)
    }

    /**
     * Retrain the models — only ever while charging and battery-not-low. Enqueue this after
     * enough new user-confirmed examples have accumulated.
     */
    suspend fun maybeScheduleTraining() {
        val pending = trainingRepository.unconsumedStateCount() +
                trainingRepository.unconsumedTransportCount()
        if (pending >= Constants.RETRAIN_EXAMPLE_THRESHOLD) scheduleTrainingNow()
    }

    fun scheduleTrainingNow() {
        // Charging + battery-not-low only (no device-idle) so it actually runs soon after the user
        // plugs in, while still being battery-friendly.
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<ModelTrainingWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(WORK_MODEL_TRAINING, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Periodic, charging-gated sync to the user's SAF destinations — runs the incremental backup and
     * the GPX auto-export, each a no-op when its own destination isn't configured. Safe to enqueue
     * whenever either is turned on. Requires a network connection because the backup destination is
     * typically a cloud provider (Drive, Dropbox).
     */
    fun schedulePeriodicBackup() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_BACKUP, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /** Run an incremental backup as soon as constraints allow (after a manual "Back up now"). */
    fun enqueueBackupNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(WORK_BACKUP_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val WORK_TIMELINE_PERIODIC = "timeline-maintenance-periodic"
        const val WORK_RECORDING_WATCHDOG = "recording-watchdog"
        const val WORK_MODEL_TRAINING = "model-training"
        const val WORK_BACKUP = "saf-backup"
        const val WORK_BACKUP_NOW = "saf-backup-now"

        fun timelineMaintenanceWorkName(dayEpoch: Long): String = "timeline-maintenance-$dayEpoch"
        fun timelineMaintenanceNowWorkName(dayEpoch: Long): String =
            "timeline-maintenance-now-$dayEpoch"
    }
}
