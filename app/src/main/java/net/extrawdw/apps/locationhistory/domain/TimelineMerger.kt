package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Combines fragmented timeline entries for a day: consecutive same-mode trips (one journey split by
 * brief misclassification), and consecutive visits to the same place. Runs inside the authoritative
 * timeline-maintenance pipeline. Idempotent — re-running on an already-merged day is a no-op.
 *
 * A multi-modal journey stays as several trips (walk → bus → walk); only *same-mode* adjacent trips
 * are fused.
 */
@Singleton
class TimelineMerger @Inject constructor(
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val sampleDao: LocationSampleDao,
) {

    suspend fun mergeDay(dayEpoch: Long) {
        removeEmptyTrips(dayEpoch)
        // Drop GPS-drift "trips" that sit between two visits to the same place.
        removeDriftTrips(dayEpoch)
        // Repeat until stable so chains (A=B=C…) collapse fully.
        var guard = 0
        while (guard++ < 12 && (mergeAdjacentVisitsOnce(dayEpoch) || mergeAdjacentTripsOnce(dayEpoch))) {
            // keep going
        }
    }

    private suspend fun removeEmptyTrips(dayEpoch: Long) {
        for (trip in tripDao.byDay(dayEpoch)) {
            if (trip.confirmed) continue
            if (trip.distanceMeters <= 0.0 || trip.encodedPolyline.isEmpty()) {
                tripDao.deleteTrip(trip.id)
            }
        }
    }

    /** Delete trips whose net displacement is below the drift threshold and that are bounded by two
     *  visits to the same place — these are GPS jitter, not real movement — and fuse the two visits
     *  (the gap they leave behind is the drift trip's own duration, so the normal gap rule wouldn't
     *  bridge it). */
    private suspend fun removeDriftTrips(dayEpoch: Long) {
        for (trip in tripDao.byDay(dayEpoch)) {
            if (trip.confirmed) continue
            val visits = visitDao.byDay(dayEpoch).sortedBy { it.startMs }
            val before = visits.lastOrNull { it.startMs <= trip.startMs }
            val after = visits.firstOrNull { it.startMs >= trip.endMs }
            val sameBoundingPlace = before?.placeId != null && before.placeId == after?.placeId
            if (sameBoundingPlace && netDisplacement(trip) < Constants.DRIFT_DISPLACEMENT_METERS) {
                tripDao.deleteTrip(trip.id)
                if (before.id != after.id) {
                    visitDao.update(mergedVisit(before, after))
                    visitDao.delete(after.id)
                }
            }
        }
    }

    /** Straight-line distance between a trip's first and last recorded points. */
    private fun netDisplacement(trip: TripEntity): Double {
        val pts = Geo.decodePolyline(trip.encodedPolyline)
        val first = pts.firstOrNull()
        val last = pts.lastOrNull()
        if (first == null || last == null) return 0.0
        return Geo.distanceMeters(first.first, first.second, last.first, last.second)
    }

    /** Merge the first mergeable adjacent same-place visit pair found. Returns true if it merged. */
    private suspend fun mergeAdjacentVisitsOnce(dayEpoch: Long): Boolean {
        val visits = visitDao.byDay(dayEpoch).sortedBy { it.startMs }
        for (i in 0 until visits.size - 1) {
            val a = visits[i]
            val b = visits[i + 1]
            // Two stays at the same place with no real trip recorded between them are one stay,
            // regardless of the gap (any movement worth showing would have left a trip).
            if (samePlace(a, b) && !tripExistsBetween(dayEpoch, a.endMs, b.startMs)) {
                visitDao.update(mergedVisit(a, b))
                visitDao.delete(b.id)
                return true
            }
        }
        return false
    }

    private suspend fun mergeAdjacentTripsOnce(dayEpoch: Long): Boolean {
        val trips = tripDao.byDay(dayEpoch).sortedBy { it.startMs }
        for (i in 0 until trips.size - 1) {
            val a = trips[i]
            val b = trips[i + 1]
            if (a.confirmed || b.confirmed) continue
            // Only fuse *same-mode* adjacent trips — different modes are a real multi-modal journey.
            if (a.mode == b.mode &&
                b.startMs - a.endMs in 0..Constants.MERGE_GAP_MS &&
                !visitExistsBetween(dayEpoch, a.endMs, b.startMs)
            ) {
                val points = Geo.decodePolyline(a.encodedPolyline) + Geo.decodePolyline(b.encodedPolyline)
                tripDao.update(
                    a.copy(
                        endMs = maxOf(a.endMs, b.endMs),
                        distanceMeters = a.distanceMeters + b.distanceMeters,
                        encodedPolyline = Geo.encodePolyline(points),
                        modeConfidence = maxOf(a.modeConfidence, b.modeConfidence),
                    ),
                )
                tripDao.deleteTrip(b.id)
                return true
            }
        }
        return false
    }

    private fun samePlace(a: VisitEntity, b: VisitEntity): Boolean = when {
        a.placeId != null && a.placeId == b.placeId -> true
        a.placeId == null && b.placeId == null && a.candidateName != null ->
            a.candidateName == b.candidateName
        else -> false
    }

    /**
     * Merge two visits, then **recompute the merged visit's geometry from the actual samples** in
     * the combined span — so center, radius, sampleCount and reliability reflect everything the stay
     * now covers (including a reclassified trip's fixes), rather than carrying the first visit's
     * stale values. Falls back to the metadata merge if the span has too few usable samples.
     */
    private suspend fun mergedVisit(a: VisitEntity, b: VisitEntity): VisitEntity {
        val base = mergeVisits(a, b)
        val samples = sampleDao.rangeForComputation(base.startMs, base.endMs + 1)
        if (samples.size < 2) return base
        val geom = VisitGeometry.compute(samples, base.centroidLatitude, base.centroidLongitude)
        return base.copy(
            centroidLatitude = geom.latitude,
            centroidLongitude = geom.longitude,
            radiusMeters = geom.radiusMeters,
            sampleCount = samples.size,
            reliability = geom.reliability.toFloat(),
        )
    }

    private fun mergeVisits(a: VisitEntity, b: VisitEntity): VisitEntity {
        val aDuration = (a.endMs - a.startMs).coerceAtLeast(1L).toDouble()
        val bDuration = (b.endMs - b.startMs).coerceAtLeast(1L).toDouble()
        val total = aDuration + bDuration
        return a.copy(
            placeId = a.placeId ?: b.placeId,
            candidateName = a.candidateName ?: b.candidateName,
            candidateGooglePlaceId = a.candidateGooglePlaceId ?: b.candidateGooglePlaceId,
            candidateLatitude = a.candidateLatitude ?: b.candidateLatitude,
            candidateLongitude = a.candidateLongitude ?: b.candidateLongitude,
            endMs = maxOf(a.endMs, b.endMs),
            centroidLatitude = (a.centroidLatitude * aDuration + b.centroidLatitude * bDuration) / total,
            centroidLongitude = (a.centroidLongitude * aDuration + b.centroidLongitude * bDuration) / total,
            radiusMeters = maxOf(a.radiusMeters, b.radiusMeters),
            confirmed = a.confirmed || b.confirmed,
            confidence = maxOf(a.confidence, b.confidence),
            isOngoing = a.isOngoing || b.isOngoing,
        )
    }

    private suspend fun tripExistsBetween(dayEpoch: Long, fromMs: Long, toMs: Long): Boolean =
        tripDao.byDay(dayEpoch).any { it.startMs < toMs && it.endMs > fromMs }

    private suspend fun visitExistsBetween(dayEpoch: Long, fromMs: Long, toMs: Long): Boolean =
        visitDao.byDay(dayEpoch).any { it.startMs < toMs && it.endMs > fromMs }
}
