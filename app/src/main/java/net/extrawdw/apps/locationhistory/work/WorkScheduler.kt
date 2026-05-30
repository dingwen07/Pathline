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
import net.extrawdw.apps.locationhistory.data.repo.TrainingRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Central place that enqueues all deferred background work with the right constraints. */
@Singleton
class WorkScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val trainingRepository: TrainingRepository,
) {

    /** Build the trip + segments between two visits, off the recording hot path. */
    fun enqueueSegmentation(fromVisitId: Long, toVisitId: Long) {
        val request = OneTimeWorkRequestBuilder<SegmentationWorker>()
            .setInputData(
                workDataOf(
                    SegmentationWorker.KEY_FROM to fromVisitId,
                    SegmentationWorker.KEY_TO to toVisitId,
                ),
            )
            .build()
        workManager.enqueueUniqueWork(
            "segmentation-$fromVisitId-$toVisitId", ExistingWorkPolicy.REPLACE, request,
        )
    }

    /** Combine fragmented visits/trips for a day. Expedited so it runs immediately when active. */
    fun enqueueMerge(dayEpoch: Long) {
        val request = OneTimeWorkRequestBuilder<MergeWorker>()
            .setInputData(workDataOf(MergeWorker.KEY_DAY to dayEpoch))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork("merge-$dayEpoch", ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Retrain the models — only ever while charging, idle and battery-not-low. Enqueue this after
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
        workManager.enqueueUniqueWork("model-training", ExistingWorkPolicy.KEEP, request)
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
            "sample-export", ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }
}
