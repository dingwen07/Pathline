package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.RecordingRepository
import net.extrawdw.apps.locationhistory.domain.TripSegmenter

/**
 * Builds the trip (and its transport-mode segments) for the movement between two consecutive
 * visits. Runs off the recording hot path as deferred background work.
 */
@HiltWorker
class SegmentationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val visitDao: VisitDao,
    private val locationRepository: LocationRepository,
    private val recordingRepository: RecordingRepository,
    private val tripSegmenter: TripSegmenter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val fromVisitId = inputData.getLong(KEY_FROM, -1L)
        val toVisitId = inputData.getLong(KEY_TO, -1L)
        val fromVisit = visitDao.byId(fromVisitId) ?: return Result.success()
        val toVisit = visitDao.byId(toVisitId) ?: return Result.success()

        val startMs = fromVisit.endMs
        val endMs = toVisit.startMs
        if (endMs <= startMs) return Result.success()

        val samples = locationRepository.rangeForComputation(startMs, endMs + 1)
        val segments = tripSegmenter.segment(samples)
        if (segments.isNotEmpty()) {
            recordingRepository.saveTripWithSegments(fromVisitId, toVisitId, startMs, endMs, segments)
        }
        return Result.success()
    }

    companion object {
        const val KEY_FROM = "from_visit_id"
        const val KEY_TO = "to_visit_id"
    }
}
