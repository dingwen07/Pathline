package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.domain.TimelineMerger

/** Runs the [TimelineMerger] for a day to combine fragmented visits/trips. Expedited so it lands
 *  near-instantly while the user is active. */
@HiltWorker
class MergeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val merger: TimelineMerger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val day = inputData.getLong(KEY_DAY, TimeBuckets.dayEpoch(System.currentTimeMillis()))
        net.extrawdw.apps.locationhistory.core.AppLog.i("MergeWorker", "mergeDay $day")
        merger.mergeDay(day)
        return Result.success()
    }

    companion object {
        const val KEY_DAY = "day_epoch"
    }
}
