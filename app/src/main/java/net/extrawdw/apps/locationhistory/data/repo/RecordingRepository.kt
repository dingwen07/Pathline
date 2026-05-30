package net.extrawdw.apps.locationhistory.data.repo

import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.domain.SegmentResult
import javax.inject.Inject
import javax.inject.Singleton

/** Persistence helper for derived trips. */
@Singleton
class RecordingRepository @Inject constructor(
    private val tripDao: TripDao,
) {
    /**
     * Persist a journey's movement runs as **one trip per single-mode run** (so a multi-modal
     * journey becomes consecutive trips). [fromVisitId]/[toVisitId] tag every run with the bounding
     * visits so a door-to-door journey can be recomputed by grouping them.
     */
    suspend fun saveTrips(
        fromVisitId: Long?,
        toVisitId: Long?,
        runs: List<SegmentResult>,
    ) {
        for (run in runs) {
            tripDao.insert(
                TripEntity(
                    fromVisitId = fromVisitId,
                    toVisitId = toVisitId,
                    startMs = run.startMs,
                    endMs = run.endMs,
                    dayEpoch = TimeBuckets.dayEpoch(run.startMs),
                    mode = run.mode,
                    modeConfidence = run.confidence,
                    encodedPolyline = run.encodedPolyline,
                    distanceMeters = run.distanceMeters,
                    confirmed = false,
                ),
            )
        }
    }
}
