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
    private val placeRepository: net.extrawdw.apps.locationhistory.data.repo.PlaceRepository,
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
            tripDao.deleteUnconfirmedOverlapping(dayStart, dayEnd)
            visitDao.deleteUnconfirmedOverlapping(dayStart, dayEnd)
        }

        if (samples.isEmpty()) return Result.success()

        val confirmedVisits = visitDao.confirmedOverlapping(dayStart, dayEnd)
        val latest = sampleDao.mostRecent()
        val candidates = visitDetector.detectVisits(samples)
            .filter { it.overlaps(dayStart, dayEnd) }
            .filterNot { candidate -> confirmedVisits.any { it.overlaps(candidate.startMs, candidate.endMs + 1) } }
            .toMutableList()

        ongoingStationaryCandidate(samples, dayStart, dayEnd, latest)?.let { ongoing ->
            val overlapsDetected = candidates.any { it.overlaps(ongoing.startMs, ongoing.endMs + 1) }
            val overlapsConfirmed = confirmedVisits.any { it.overlaps(ongoing.startMs, ongoing.endMs + 1) }
            if (!overlapsDetected && !overlapsConfirmed) candidates.add(ongoing)
        }

        val insertedVisitIds = ArrayList<Long>(candidates.size)
        for (candidate in candidates) {
            val inserted = materializeVisit(candidate, samples, latest)
            if (inserted != null) insertedVisitIds.add(inserted)
        }

        // Confirming a visit locks its *place*, not its *clock*: a confirmed visit that is still
        // ongoing must keep extending while the user is there, and finalize once they move on.
        // (Maintenance otherwise leaves confirmed visits untouched, which froze the end time.)
        extendConfirmedOngoingVisits(confirmedVisits, latest, System.currentTimeMillis())

        rebuildTrips(dayStart, dayEnd)
        // Show the trip that is *currently in progress* (you've left a place but not arrived yet).
        // rebuildTrips needs both endpoints, so the tail after the last visit is otherwise invisible
        // until you become stationary.
        buildOngoingTrip(dayStart, dayEnd, latest)
        merger.mergeDay(day)

        // Let matched places drift toward where the user actually goes. recordVisitToPlace weights
        // confirmed visits 4x and decays old ones, so auto-detected visits nudge the center/radius
        // while confirmations anchor it. Fixed places are skipped inside recordVisitToPlace.
        visitDao.byDay(day).mapNotNull { it.placeId }.distinct()
            .forEach { placeRepository.recordVisitToPlace(it) }

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
                sampleCount = clean.size,
                reliability = geom.reliability.toFloat(),
                confirmed = false,
                confidence = 0f,
                isOngoing = isOngoing,
            ),
            match,
        ))
    }

    /**
     * Keep the *single* current confirmed visit's end time tracking the present while the device is
     * still stationary near it, and finalize it once the user moves on. **At most one visit may be
     * ongoing**, so any other confirmed visit still flagged `isOngoing` (stale flags from confirming
     * while ongoing) is finalized here — otherwise every one of them would be extended to the same
     * `now`, producing duplicate end times. Confirmed visits are otherwise preserved by maintenance.
     */
    private suspend fun extendConfirmedOngoingVisits(
        confirmedVisits: List<VisitEntity>,
        latest: LocationSampleEntity?,
        now: Long,
    ) {
        val ongoing = confirmedVisits.filter { it.isOngoing }
        if (ongoing.isEmpty()) return
        // Only the most recent stay can be ongoing; finalize the rest so they don't all get extended
        // to the same `now` (which would produce duplicate end times).
        val current = ongoing.maxByOrNull { it.startMs }!!
        ongoing.asSequence().filter { it.id != current.id }
            .forEach { visitDao.update(it.copy(isOngoing = false)) }

        if (latest == null) return
        val nearby = net.extrawdw.apps.locationhistory.core.Geo.distanceMeters(
            current.centroidLatitude, current.centroidLongitude, latest.latitude, latest.longitude,
        ) <= maxOf(current.radiusMeters, Constants.STATIONARY_RADIUS_METERS)
        val stillThere = latest.timestampMs >= current.startMs && latest.includedInComputation &&
            latest.devicePhysicalState == DevicePhysicalState.STATIONARY &&
            (latest.speed ?: 0f) <= 0.8f && nearby
        if (stillThere) {
            // Recompute the visit's own center/radius from its (now longer) sample span so the
            // visit circle stays accurate as the stay grows — this also feeds the place's
            // recency-weighted radius/center.
            val span = sampleDao.rangeForComputation(current.startMs, now + 1)
            val geom = if (span.size >= 2) {
                VisitGeometry.compute(span, current.centroidLatitude, current.centroidLongitude)
            } else {
                null
            }
            visitDao.update(
                current.copy(
                    endMs = maxOf(current.endMs, now),
                    centroidLatitude = geom?.latitude ?: current.centroidLatitude,
                    centroidLongitude = geom?.longitude ?: current.centroidLongitude,
                    radiusMeters = geom?.radiusMeters ?: current.radiusMeters,
                    sampleCount = if (geom != null) span.size else current.sampleCount,
                    reliability = geom?.reliability?.toFloat() ?: current.reliability,
                ),
            )
        } else if (latest.timestampMs > current.endMs) {
            visitDao.update(current.copy(isOngoing = false))
        }
    }

    private fun ongoingStationaryCandidate(
        samples: List<LocationSampleEntity>,
        dayStart: Long,
        dayEnd: Long,
        latest: LocationSampleEntity?,
    ): VisitCandidate? {
        val latestInDay = samples.lastOrNull { it.timestampMs >= dayStart && it.timestampMs < dayEnd }
        if (latest == null || latestInDay?.id != latest.id) return null
        if (!latest.includedInComputation || latest.devicePhysicalState != DevicePhysicalState.STATIONARY) return null
        val speed = latest.speed ?: 0f
        if (speed > 0.6f) return null
        val trustedStationary = latest.devicePhysicalStateConfidence >= 0.45f ||
            latest.arActivity?.uppercase() == "STILL"
        if (!trustedStationary) return null

        val tail = ArrayDeque<LocationSampleEntity>()
        for (sample in samples.asReversed()) {
            if (sample.timestampMs < dayStart || sample.timestampMs >= dayEnd) continue
            if (!sample.includedInComputation || sample.devicePhysicalState != DevicePhysicalState.STATIONARY) break
            if ((sample.speed ?: 0f) > 0.8f) break
            if ((sample.accuracy ?: Constants.SAMPLE_ACCURACY_GATE_METERS) >
                Constants.SAMPLE_ACCURACY_GATE_METERS
            ) {
                break
            }
            tail.addFirst(sample)
        }
        if (tail.isEmpty()) tail.add(latest)
        val good = tail.toList()
        val geom = VisitGeometry.compute(good, latest.latitude, latest.longitude)
        return VisitCandidate(
            startMs = good.first().timestampMs,
            endMs = maxOf(good.last().timestampMs, System.currentTimeMillis()),
            centroidLatitude = geom.latitude,
            centroidLongitude = geom.longitude,
            sampleCount = good.size,
        )
    }

    /**
     * Materialize the in-progress trip: when the latest fix shows the user is moving, segment the
     * movement from the most recent visit's end up to now and persist it with no destination
     * (`toVisitId = null`). Rebuilt each pass so it extends as movement continues; once the user
     * arrives and a destination visit forms, [rebuildTrips] replaces it with the bounded trip.
     */
    private suspend fun buildOngoingTrip(dayStart: Long, dayEnd: Long, latest: LocationSampleEntity?) {
        if (latest == null || latest.devicePhysicalState == DevicePhysicalState.STATIONARY) return
        val visits = visitDao.overlapping(dayStart, dayEnd).sortedBy { it.startMs }
        val origin = visits.lastOrNull { it.endMs <= latest.timestampMs } ?: return
        val startMs = origin.endMs
        val endMs = latest.timestampMs
        if (endMs <= startMs) return
        if (tripDao.confirmedOverlapping(startMs, endMs + 1).isNotEmpty()) return
        val movingSamples = sampleDao.rangeForComputation(startMs, endMs + 1)
        val runs = tripSegmenter.segment(movingSamples)
        if (runs.isNotEmpty()) recordingRepository.saveTrips(origin.id, null, runs)
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
            val runs = tripSegmenter.segment(movingSamples)
            if (runs.isNotEmpty()) {
                recordingRepository.saveTrips(from.id, to.id, runs)
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
