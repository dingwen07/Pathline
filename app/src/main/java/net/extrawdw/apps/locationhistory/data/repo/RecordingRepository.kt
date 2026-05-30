package net.extrawdw.apps.locationhistory.data.repo

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.TripSegmentEntity
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.domain.PlaceMatch
import net.extrawdw.apps.locationhistory.domain.SegmentResult
import net.extrawdw.apps.locationhistory.domain.VisitCandidate
import javax.inject.Inject
import javax.inject.Singleton

/** Persistence of the derived recording artifacts: visits, trips and their segments. */
@Singleton
class RecordingRepository @Inject constructor(
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
) {
    suspend fun ongoingVisit(): VisitEntity? = visitDao.ongoing()
    suspend fun mostRecentVisit(): VisitEntity? = visitDao.mostRecent()

    /** Open an ongoing visit anchored at a stationary cluster, applying a place match if found. */
    suspend fun openVisit(candidate: VisitCandidate, match: PlaceMatch?): Long {
        val base = VisitEntity(
            placeId = null,
            candidateName = null,
            candidateGooglePlaceId = null,
            candidateLatitude = null,
            candidateLongitude = null,
            startMs = candidate.startMs,
            endMs = candidate.endMs,
            dayEpoch = candidate.dayEpoch,
            centroidLatitude = candidate.centroidLatitude,
            centroidLongitude = candidate.centroidLongitude,
            confirmed = false,
            confidence = 0f,
            isOngoing = true,
        )
        return visitDao.insert(applyMatch(base, match))
    }

    /** Close the ongoing visit, stamping its end time. */
    suspend fun closeVisit(visitId: Long, endMs: Long) {
        val v = visitDao.byId(visitId) ?: return
        visitDao.update(v.copy(endMs = endMs, isOngoing = false))
    }

    /** Grow the ongoing visit's end time so its duration updates live on the timeline. */
    suspend fun extendOngoingVisit(endMs: Long) {
        val v = visitDao.ongoing() ?: return
        if (endMs > v.endMs) visitDao.update(v.copy(endMs = endMs))
    }

    suspend fun applyMatchToVisit(visitId: Long, match: PlaceMatch) {
        val v = visitDao.byId(visitId) ?: return
        visitDao.update(applyMatch(v, match))
    }

    private fun applyMatch(visit: VisitEntity, match: PlaceMatch?): VisitEntity = when (match) {
        is PlaceMatch.Local -> visit.copy(
            placeId = match.place.id,
            candidateName = match.place.name,
            confidence = match.confidence,
            // A confident match against a user-confirmed place is trustworthy ground truth.
            confirmed = match.place.confirmed && match.confidence >= Constants.CONFIRM_CONFIDENCE_THRESHOLD,
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

    /** Replace a trip's segments atomically (used after (re)segmentation). */
    suspend fun saveTripWithSegments(
        fromVisitId: Long?,
        toVisitId: Long?,
        startMs: Long,
        endMs: Long,
        segments: List<SegmentResult>,
    ): Long {
        val trip = TripEntity(
            fromVisitId = fromVisitId,
            toVisitId = toVisitId,
            startMs = startMs,
            endMs = endMs,
            dayEpoch = TimeBuckets.dayEpoch(startMs),
            distanceMeters = segments.sumOf { it.distanceMeters },
            confirmed = false,
        )
        val tripId = tripDao.insert(trip)
        tripDao.deleteSegmentsForTrip(tripId)
        for (s in segments) {
            tripDao.insertSegment(
                TripSegmentEntity(
                    tripId = tripId,
                    startMs = s.startMs,
                    endMs = s.endMs,
                    mode = s.mode,
                    modeConfidence = s.confidence,
                    confirmed = false,
                    encodedPolyline = s.encodedPolyline,
                    distanceMeters = s.distanceMeters,
                ),
            )
        }
        return tripId
    }
}
