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

    /** Periodic, charging-gated export of monthly sample partitions for efficient backup. */
    fun schedulePeriodicExport() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val request = PeriodicWorkRequestBuilder<ExportWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_SAMPLE_EXPORT, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    companion object {
        const val WORK_TIMELINE_PERIODIC = "timeline-maintenance-periodic"
        const val WORK_MODEL_TRAINING = "model-training"
        const val WORK_SAMPLE_EXPORT = "sample-export"

        fun timelineMaintenanceWorkName(dayEpoch: Long): String = "timeline-maintenance-$dayEpoch"
        fun timelineMaintenanceNowWorkName(dayEpoch: Long): String = "timeline-maintenance-now-$dayEpoch"
    }
}
