package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.data.repo.RecordingRepository
import net.extrawdw.apps.locationhistory.domain.PlaceMatcher
import net.extrawdw.apps.locationhistory.domain.TimelineMerger
import net.extrawdw.apps.locationhistory.domain.TimelineRebuilder
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import net.extrawdw.apps.locationhistory.domain.TripSegmenter
import net.extrawdw.apps.locationhistory.domain.VisitDetector

/**
 * Authoritative maintenance entry point for derived timeline state. The foreground recorder only
 * appends samples and labels light state; this worker hands the affected day to
 * [TimelineRebuilder] — the actual pipeline (detect stays, persist geometry, match places,
 * segment transport, merge, cleanup), kept as a plain JVM-testable class. Here lives only the
 * WorkManager plumbing and the Android-bound seams the rebuilder takes as functions.
 */
@HiltWorker
class TimelineMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val sampleDao: LocationSampleDao,
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val locationRepository: LocationRepository,
    private val recordingRepository: RecordingRepository,
    private val placeRepository: PlaceRepository,
    private val visitDetector: VisitDetector,
    private val placeMatcher: PlaceMatcher,
    private val tripSegmenter: TripSegmenter,
    private val merger: TimelineMerger,
    private val timelineWriteLock: TimelineWriteLock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val day = inputData.getLong(KEY_DAY, TimeBuckets.dayEpoch(System.currentTimeMillis()))
        val reason = inputData.getString(KEY_REASON) ?: "unspecified"
        AppLog.i(TAG, "maintenance day=$day reason=$reason")

        val rebuilder = TimelineRebuilder(
            sampleDao = sampleDao,
            visitDao = visitDao,
            tripDao = tripDao,
            locationRepository = locationRepository,
            recordingRepository = recordingRepository,
            placeRepository = placeRepository,
            visitDetector = visitDetector,
            merger = merger,
            matchPlace = placeMatcher::match,
            segmentTrips = tripSegmenter::segment,
            inTransaction = { block -> db.withTransaction { block() } },
            log = { AppLog.w(TAG, it) },
        )
        // The whole rebuild holds the timeline write lock, so a user confirmation or hand edit can
        // never interleave with the delete-unconfirmed/reinsert sweep (which would silently drop it).
        val visits = timelineWriteLock.withLock { rebuilder.rebuildDay(day) }

        AppLog.i(TAG, "maintenance complete day=$day visits=$visits")
        return Result.success()
    }

    companion object {
        const val KEY_DAY = "day_epoch"
        const val KEY_REASON = "reason"
        private const val TAG = "TimelineMaintenance"
    }
}
