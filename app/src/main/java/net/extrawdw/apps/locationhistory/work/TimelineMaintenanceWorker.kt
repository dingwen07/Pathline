package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.RecordingRepository
import net.extrawdw.apps.locationhistory.domain.PlaceMatch
import net.extrawdw.apps.locationhistory.domain.PlaceMatcher
import net.extrawdw.apps.locationhistory.domain.TimelineMerger
import net.extrawdw.apps.locationhistory.domain.TripSegmenter
import net.extrawdw.apps.locationhistory.domain.VisitCandidate
import net.extrawdw.apps.locationhistory.domain.VisitDetector
import net.extrawdw.apps.locationhistory.domain.VisitGeometry

/**
 * Authoritative maintenance pipeline for derived timeline state.
 *
 * The foreground recorder only appends samples and labels light state. This worker reads those raw
 * facts, rebuilds unconfirmed visits/trips for the affected day, persists visit geometry, matches
 * places, segments transport, and runs cleanup/merge. User-confirmed rows are left in place and
 * treated as ground truth.
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
    private val visitDetector: VisitDetector,
    private val placeMatcher: PlaceMatcher,
    private val tripSegmenter: TripSegmenter,
    private val merger: TimelineMerger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val day = inputData.getLong(KEY_DAY, TimeBuckets.dayEpoch(System.currentTimeMillis()))
        val reason = inputData.getString(KEY_REASON) ?: "unspecified"
        AppLog.i(TAG, "maintenance day=$day reason=$reason")

        val range = TimeBuckets.dayRangeMillis(day)
        val dayStart = range.first
        val dayEnd = range.last + 1
        val computeStart = dayStart - Constants.MIN_VISIT_DURATION_MS
        val computeEnd = dayEnd + Constants.MIN_VISIT_DURATION_MS
        val samples = sampleDao.range(computeStart, computeEnd)

        db.withTransaction {
            tripDao.deleteSegmentsForUnconfirmedTripsOverlapping(dayStart, dayEnd)
            tripDao.deleteUnconfirmedOverlapping(dayStart, dayEnd)
            visitDao.deleteUnconfirmedOverlapping(dayStart, dayEnd)
        }

        if (samples.isEmpty()) return Result.success()

        val confirmedVisits = visitDao.confirmedOverlapping(dayStart, dayEnd)
        val latest = sampleDao.mostRecent()
        val candidates = visitDetector.detectVisits(samples)
            .filter { it.overlaps(dayStart, dayEnd) }
            .filterNot { candidate -> confirmedVisits.any { it.overlaps(candidate.startMs, candidate.endMs + 1) } }

        val insertedVisitIds = ArrayList<Long>(candidates.size)
        for (candidate in candidates) {
            val inserted = materializeVisit(candidate, samples, latest)
            if (inserted != null) insertedVisitIds.add(inserted)
        }

        rebuildTrips(dayStart, dayEnd)
        merger.mergeDay(day)
        AppLog.i(TAG, "maintenance complete day=$day visits=${insertedVisitIds.size}")
        return Result.success()
    }

    private suspend fun materializeVisit(
        candidate: VisitCandidate,
        samples: List<LocationSampleEntity>,
        latest: LocationSampleEntity?,
    ): Long? {
        val span = samples.inRange(candidate.startMs, candidate.endMs + 1)
        val usable = span.filter { it.includedInComputation }.ifEmpty { span }
        if (usable.isEmpty()) return null

        val initialGeom = VisitGeometry.compute(
            usable,
            candidate.centroidLatitude,
            candidate.centroidLongitude,
        )
        val match = runCatching {
            placeMatcher.match(initialGeom.latitude, initialGeom.longitude)
        }.onFailure {
            AppLog.w(TAG, "place match failed; keeping visit unmatched")
        }.getOrNull()

        if (match is PlaceMatch.Local) {
            locationRepository.excludeDriftOutside(
                startMs = candidate.startMs,
                endMs = candidate.endMs,
                centerLat = match.place.latitude,
                centerLon = match.place.longitude,
                radiusMeters = match.place.radiusMeters,
            )
        }

        val clean = sampleDao.rangeForComputation(candidate.startMs, candidate.endMs + 1)
            .ifEmpty { usable }
        val fallbackLat = (match as? PlaceMatch.Local)?.place?.latitude ?: initialGeom.latitude
        val fallbackLon = (match as? PlaceMatch.Local)?.place?.longitude ?: initialGeom.longitude
        val geom = VisitGeometry.compute(clean, fallbackLat, fallbackLon)
        val isOngoing = latest != null &&
            latest.timestampMs in candidate.startMs..candidate.endMs &&
            latest.devicePhysicalState == DevicePhysicalState.STATIONARY

        return visitDao.insert(applyMatch(
            VisitEntity(
                placeId = null,
                candidateName = null,
                candidateGooglePlaceId = null,
                candidateLatitude = null,
                candidateLongitude = null,
                startMs = candidate.startMs,
                endMs = candidate.endMs,
                dayEpoch = candidate.dayEpoch,
                centroidLatitude = geom.latitude,
                centroidLongitude = geom.longitude,
                radiusMeters = geom.radiusMeters,
                confirmed = false,
                confidence = 0f,
                isOngoing = isOngoing,
            ),
            match,
        ))
    }

    private suspend fun rebuildTrips(dayStart: Long, dayEnd: Long) {
        val visits = visitDao.overlapping(dayStart, dayEnd).sortedBy { it.startMs }
        if (visits.size < 2) return
        val confirmedTrips = tripDao.confirmedOverlapping(dayStart, dayEnd)
        for (i in 0 until visits.lastIndex) {
            val from = visits[i]
            val to = visits[i + 1]
            val startMs = from.endMs
            val endMs = to.startMs
            if (endMs <= startMs || endMs <= dayStart || startMs >= dayEnd) continue
            if (confirmedTrips.any { it.overlaps(startMs, endMs + 1) }) continue
            val movingSamples = sampleDao.rangeForComputation(startMs, endMs + 1)
            val segments = tripSegmenter.segment(movingSamples)
            if (segments.isNotEmpty()) {
                recordingRepository.saveTripWithSegments(from.id, to.id, startMs, endMs, segments)
            }
        }
    }

    private fun applyMatch(visit: VisitEntity, match: PlaceMatch?): VisitEntity = when (match) {
        is PlaceMatch.Local -> visit.copy(
            placeId = match.place.id,
            candidateName = match.place.name,
            confidence = match.confidence,
            // Maintenance can be highly confident, but user/editor confirmations are the only
            // ground truth rows that should survive future rebuilds unchanged.
            confirmed = false,
        )
        is PlaceMatch.Candidate -> visit.copy(
            candidateName = match.candidate.name,
            candidateGooglePlaceId = match.candidate.googlePlaceId,
            candidateLatitude = match.candidate.latitude,
            candidateLongitude = match.candidate.longitude,
            confidence = match.confidence,
            confirmed = false,
        )
        PlaceMatch.None, null -> visit
    }

    private fun VisitCandidate.overlaps(startMs: Long, endMs: Long): Boolean =
        this.startMs < endMs && this.endMs > startMs

    private fun VisitEntity.overlaps(startMs: Long, endMs: Long): Boolean =
        this.startMs < endMs && this.endMs > startMs

    private fun net.extrawdw.apps.locationhistory.data.db.TripEntity.overlaps(
        startMs: Long,
        endMs: Long,
    ): Boolean = this.startMs < endMs && this.endMs > startMs

    private fun List<LocationSampleEntity>.inRange(startMs: Long, endMs: Long): List<LocationSampleEntity> =
        filter { it.timestampMs >= startMs && it.timestampMs < endMs }

    companion object {
        const val KEY_DAY = "day_epoch"
        const val KEY_REASON = "reason"
        private const val TAG = "TimelineMaintenance"
    }
}
