package net.extrawdw.apps.locationhistory.data.repo

import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.TripSegmentEntity
import net.extrawdw.apps.locationhistory.domain.SegmentResult
import javax.inject.Inject
import javax.inject.Singleton

/** Persistence helper for derived trips and their segments. */
@Singleton
class RecordingRepository @Inject constructor(
    private val tripDao: TripDao,
) {
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
