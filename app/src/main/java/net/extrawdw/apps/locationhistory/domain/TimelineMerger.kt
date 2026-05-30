package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.data.db.TripSegmentEntity
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Combines fragmented timeline entries for a day: consecutive same-mode trip segments, adjacent
 * trips that form one journey (no visit between), and consecutive visits to the same place. Runs
 * inside the authoritative timeline-maintenance pipeline.
 * Idempotent — re-running on an already-merged day is a no-op.
 */
@Singleton
class TimelineMerger @Inject constructor(
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
) {

    suspend fun mergeDay(dayEpoch: Long) {
        tripDao.byDay(dayEpoch).forEach { mergeSegmentsWithin(it) }
        // Drop GPS-drift "trips" that sit between two visits to the same place.
        removeDriftTrips(dayEpoch)
        // Repeat until stable so chains (A=B=C…) collapse fully.
        var guard = 0
        while (guard++ < 12 && (mergeAdjacentVisitsOnce(dayEpoch) || mergeAdjacentTripsOnce(dayEpoch))) {
            // keep going
        }
        tripDao.byDay(dayEpoch).forEach { mergeSegmentsWithin(it) }
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
            if (before?.confirmed == true || after?.confirmed == true) continue
            val sameBoundingPlace = before?.placeId != null && before.placeId == after?.placeId
            if (sameBoundingPlace && netDisplacement(trip) < Constants.DRIFT_DISPLACEMENT_METERS) {
                tripDao.deleteSegmentsForTrip(trip.id)
                tripDao.deleteTrip(trip.id)
                if (before.id != after.id) {
                    visitDao.update(
                        before.copy(
                            endMs = maxOf(before.endMs, after.endMs),
                            centroidLatitude = (before.centroidLatitude + after.centroidLatitude) / 2,
                            centroidLongitude = (before.centroidLongitude + after.centroidLongitude) / 2,
                            confirmed = before.confirmed || after.confirmed,
                        ),
                    )
                    visitDao.delete(after.id)
                }
            }
        }
    }

    /** Straight-line distance between a trip's first and last recorded points. */
    private suspend fun netDisplacement(trip: TripEntity): Double {
        val segs = tripDao.segmentsForTrip(trip.id)
        val first = segs.firstOrNull()?.let { Geo.decodePolyline(it.encodedPolyline).firstOrNull() }
        val last = segs.lastOrNull()?.let { Geo.decodePolyline(it.encodedPolyline).lastOrNull() }
        if (first == null || last == null) return 0.0
        return Geo.distanceMeters(first.first, first.second, last.first, last.second)
    }

    /** Merge consecutive same-mode segments inside one trip. */
    private suspend fun mergeSegmentsWithin(trip: TripEntity) {
        val segments = tripDao.segmentsForTrip(trip.id)
        if (segments.size < 2) return
        val merged = ArrayList<TripSegmentEntity>()
        for (seg in segments) {
            val last = merged.lastOrNull()
            if (last != null && last.mode == seg.mode) {
                val points = Geo.decodePolyline(last.encodedPolyline) + Geo.decodePolyline(seg.encodedPolyline)
                merged[merged.size - 1] = last.copy(
                    endMs = seg.endMs,
                    distanceMeters = last.distanceMeters + seg.distanceMeters,
                    encodedPolyline = Geo.encodePolyline(points),
                    confirmed = last.confirmed || seg.confirmed,
                    modeConfidence = maxOf(last.modeConfidence, seg.modeConfidence),
                )
            } else {
                merged.add(seg.copy(id = 0))
            }
        }
        if (merged.size == segments.size) return // nothing collapsed
        tripDao.deleteSegmentsForTrip(trip.id)
        merged.forEach { tripDao.insertSegment(it.copy(tripId = trip.id)) }
    }

    /** Merge the first mergeable adjacent same-place visit pair found. Returns true if it merged. */
    private suspend fun mergeAdjacentVisitsOnce(dayEpoch: Long): Boolean {
        val visits = visitDao.byDay(dayEpoch).sortedBy { it.startMs }
        for (i in 0 until visits.size - 1) {
            val a = visits[i]
            val b = visits[i + 1]
            if (a.confirmed || b.confirmed) continue
            // Two stays at the same place with no real trip recorded between them are one stay,
            // regardless of the gap (any movement worth showing would have left a trip).
            if (samePlace(a, b) && !tripExistsBetween(dayEpoch, a.endMs, b.startMs)) {
                visitDao.update(
                    a.copy(
                        endMs = maxOf(a.endMs, b.endMs),
                        centroidLatitude = (a.centroidLatitude + b.centroidLatitude) / 2,
                        centroidLongitude = (a.centroidLongitude + b.centroidLongitude) / 2,
                        confirmed = a.confirmed || b.confirmed,
                    ),
                )
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
            if (b.startMs - a.endMs in 0..Constants.MERGE_GAP_MS &&
                !visitExistsBetween(dayEpoch, a.endMs, b.startMs)
            ) {
                tripDao.segmentsForTrip(b.id).forEach {
                    tripDao.insertSegment(it.copy(id = 0, tripId = a.id))
                }
                tripDao.update(
                    a.copy(endMs = maxOf(a.endMs, b.endMs), distanceMeters = a.distanceMeters + b.distanceMeters),
                )
                tripDao.deleteSegmentsForTrip(b.id)
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

    private suspend fun tripExistsBetween(dayEpoch: Long, fromMs: Long, toMs: Long): Boolean =
        tripDao.byDay(dayEpoch).any { it.startMs < toMs && it.endMs > fromMs }

    private suspend fun visitExistsBetween(dayEpoch: Long, fromMs: Long, toMs: Long): Boolean =
        visitDao.byDay(dayEpoch).any { it.startMs < toMs && it.endMs > fromMs }
}
