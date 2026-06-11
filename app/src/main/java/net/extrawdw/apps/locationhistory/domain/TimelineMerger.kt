package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.AnnotationTarget
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
 * Normalizes the derived timeline over a **time span** so it satisfies the timeline invariant: at
 * every instant you are in exactly one thing, same-place stays are a single visit, and a journey is
 * a sequence of trips that only changes rows where the *transport mode* changes.
 *
 * It fuses:
 *  - consecutive **same-place** visits that overlap or have no real trip between them — including a
 *    stay that straddles midnight, which the old per-day merge could never reach because the two
 *    halves sat in different `dayEpoch` buckets; and
 *  - consecutive **same-mode** trips with no visit between them, *even when they are confirmed* —
 *    a hand-split walk that the user never meant to break in two, or a confirmed stub left beside a
 *    freshly rebuilt run. Different modes are left as separate rows, so a real multi-modal journey
 *    (walk -> bus -> walk) is preserved.
 *
 * Everything is keyed on time (via the overlap queries), never on the `dayEpoch` bucket. Idempotent:
 * re-running on an already-normalized span is a no-op.
 */
@Singleton
class TimelineMerger @Inject constructor(
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val sampleDao: LocationSampleDao,
    private val annotationStore: AnnotationStore,
) {

    /** Normalize every visit/trip overlapping [spanStartMs, spanEndMs). */
    suspend fun merge(spanStartMs: Long, spanEndMs: Long) {
        removeEmptyTrips(spanStartMs, spanEndMs)
        // Drop GPS-drift "trips" that sit between two visits to the same place.
        removeDriftTrips(spanStartMs, spanEndMs)
        // Repeat until stable so chains (A=B=C...) collapse fully. Each pass fuses one pair, so the cap
        // is generous enough for a heavily hand-fragmented day; once stable the final pass is a no-op.
        var guard = 0
        while (guard++ < 200 &&
            (mergeAdjacentVisitsOnce(spanStartMs, spanEndMs) || mergeAdjacentTripsOnce(
                spanStartMs,
                spanEndMs
            ))
        ) {
            // keep going
        }
    }

    private suspend fun removeEmptyTrips(spanStartMs: Long, spanEndMs: Long) {
        for (trip in tripDao.overlapping(spanStartMs, spanEndMs)) {
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
    private suspend fun removeDriftTrips(spanStartMs: Long, spanEndMs: Long) {
        // One visit fetch for the whole pass, kept in step with every fuse below so later trips see
        // the merged bounds, not the pre-fuse rows. The fused visit keeps the earlier start, so the
        // in-place replacement preserves the startMs ordering.
        val visits = visitDao.overlapping(spanStartMs, spanEndMs).sortedBy { it.startMs }.toMutableList()
        for (trip in tripDao.overlapping(spanStartMs, spanEndMs)) {
            if (trip.confirmed) continue
            val before = visits.lastOrNull { it.startMs <= trip.startMs }
            val after = visits.firstOrNull { it.startMs >= trip.endMs }
            val sameBoundingPlace = before?.placeId != null && before.placeId == after?.placeId
            if (sameBoundingPlace && netDisplacement(trip) < Constants.DRIFT_DISPLACEMENT_METERS) {
                tripDao.deleteTrip(trip.id)
                if (before.id != after.id) {
                    val merged = mergedVisit(before, after)
                    visitDao.update(merged)
                    // before is the older survivor; fold the dying visit's annotations onto it.
                    if (after.confirmed) {
                        annotationStore.foldOnMerge(AnnotationTarget.VISIT, before.id, after.id)
                    }
                    visitDao.delete(after.id)
                    visits[visits.indexOfFirst { it.id == before.id }] = merged
                    visits.removeAll { it.id == after.id }
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
    private suspend fun mergeAdjacentVisitsOnce(spanStartMs: Long, spanEndMs: Long): Boolean {
        val visits = visitDao.overlapping(spanStartMs, spanEndMs).sortedBy { it.startMs }
        for (i in 0 until visits.size - 1) {
            val a = visits[i]
            val b = visits[i + 1]
            if (!samePlace(a, b)) continue
            // Two stays at the same place are one stay when they overlap/touch (a stay that crossed
            // a day boundary, or a re-detected duplicate), or when no real trip was recorded between
            // them (any movement worth showing would have left a trip). Keyed on time, not dayEpoch,
            // so a midnight-spanning stay finally collapses to a single row.
            val overlapsOrTouches = b.startMs <= a.endMs
            if (overlapsOrTouches || !tripExistsBetween(
                    spanStartMs,
                    spanEndMs,
                    a.endMs,
                    b.startMs
                )
            ) {
                visitDao.update(mergedVisit(a, b))
                // a is older and its id survives; fold b's annotations onto it before b is deleted.
                // Only confirmed rows can carry annotations, so unconfirmed merges skip the lookup.
                if (b.confirmed) annotationStore.foldOnMerge(AnnotationTarget.VISIT, a.id, b.id)
                visitDao.delete(b.id)
                return true
            }
        }
        return false
    }

    private suspend fun mergeAdjacentTripsOnce(spanStartMs: Long, spanEndMs: Long): Boolean {
        val trips = tripDao.overlapping(spanStartMs, spanEndMs).sortedBy { it.startMs }
        for (i in 0 until trips.size - 1) {
            val a = trips[i]
            val b = trips[i + 1]
            // Only fuse *same-mode* adjacent trips — a mode change is a real multi-modal leg and must
            // stay its own row. Confirmed trips ARE fused here (unlike same-place visits there is no
            // ambiguity once the mode matches): a hand-split walk, or a confirmed stub sitting next to
            // a rebuilt run, is one journey leg.
            if (a.mode != b.mode) continue
            if (visitExistsBetween(spanStartMs, spanEndMs, a.endMs, b.startMs)) continue
            // Overlapping (gap < 0), touching, or within the merge gap all fuse; a wider gap is two
            // separate legs of the same mode (e.g. two walks with a stop between) and is left alone.
            if (b.startMs - a.endMs > Constants.MERGE_GAP_MS) continue

            // Chaining a's points then b's is only chronological when b starts after a ends. If they
            // overlap in time -- e.g. a converted stay (a tight cluster of fixes) sitting *inside* a
            // longer walk -- appending b's cluster after a's far endpoint draws a straight spike back
            // to it, so rebuild from the fixes in time order. The consecutive case just concatenates.
            val (polyline, distance) =
                if (b.startMs < a.endMs) {
                    rebuildTripGeometry(minOf(a.startMs, b.startMs), maxOf(a.endMs, b.endMs))
                        ?: concatGeometry(a, b)
                } else {
                    concatGeometry(a, b)
                }
            tripDao.update(
                a.copy(
                    startMs = minOf(a.startMs, b.startMs),
                    toVisitId = b.toVisitId ?: a.toVisitId,
                    endMs = maxOf(a.endMs, b.endMs),
                    distanceMeters = distance,
                    encodedPolyline = polyline,
                    modeConfidence = maxOf(a.modeConfidence, b.modeConfidence),
                    confirmed = a.confirmed || b.confirmed,
                ),
            )
            // a's id survives; fold b's annotations onto it. Confirmed trips can be fused (and only
            // confirmed rows carry annotations), so this lookup only runs when b could have content.
            if (b.confirmed) annotationStore.foldOnMerge(AnnotationTarget.TRIP, a.id, b.id)
            tripDao.deleteTrip(b.id)
            return true
        }
        return false
    }

    /** Fuse two trips' geometry by chaining their polylines end-to-end (correct when b follows a). */
    private fun concatGeometry(a: TripEntity, b: TripEntity): Pair<String, Double> {
        val points = Geo.decodePolyline(a.encodedPolyline) + Geo.decodePolyline(b.encodedPolyline)
        return Geo.encodePolyline(points) to Geo.pathLengthMeters(points)
    }

    /**
     * Rebuild a trip's polyline + distance from the time-ordered fixes in [startMs, endMs] so the
     * path follows the order they were actually recorded, not the order two overlapping trips happen
     * to be chained in. Returns null when the span has too few usable fixes to form a line.
     */
    private suspend fun rebuildTripGeometry(startMs: Long, endMs: Long): Pair<String, Double>? {
        val points = sampleDao.rangeForComputation(startMs, endMs + 1)
            .map { it.latitude to it.longitude }
        if (points.size < 2) return null
        return Geo.encodePolyline(points) to Geo.pathLengthMeters(points)
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
        // a/b can overlap (a midnight-spanning stay re-detected on both sides), so derive the merged
        // bounds from the union, not by assuming b starts after a ends.
        val startMs = minOf(a.startMs, b.startMs)
        val endMs = maxOf(a.endMs, b.endMs)
        val aDuration = (a.endMs - a.startMs).coerceAtLeast(1L).toDouble()
        val bDuration = (b.endMs - b.startMs).coerceAtLeast(1L).toDouble()
        val total = aDuration + bDuration
        return a.copy(
            placeId = a.placeId ?: b.placeId,
            candidateName = a.candidateName ?: b.candidateName,
            candidateGooglePlaceId = a.candidateGooglePlaceId ?: b.candidateGooglePlaceId,
            candidateLatitude = a.candidateLatitude ?: b.candidateLatitude,
            candidateLongitude = a.candidateLongitude ?: b.candidateLongitude,
            startMs = startMs,
            endMs = endMs,
            centroidLatitude = (a.centroidLatitude * aDuration + b.centroidLatitude * bDuration) / total,
            centroidLongitude = (a.centroidLongitude * aDuration + b.centroidLongitude * bDuration) / total,
            radiusMeters = maxOf(a.radiusMeters, b.radiusMeters),
            confirmed = a.confirmed || b.confirmed,
            confidence = maxOf(a.confidence, b.confidence),
            isOngoing = a.isOngoing || b.isOngoing,
        )
    }

    private suspend fun tripExistsBetween(
        spanStartMs: Long,
        spanEndMs: Long,
        fromMs: Long,
        toMs: Long,
    ): Boolean =
        tripDao.overlapping(spanStartMs, spanEndMs).any { it.startMs < toMs && it.endMs > fromMs }

    private suspend fun visitExistsBetween(
        spanStartMs: Long,
        spanEndMs: Long,
        fromMs: Long,
        toMs: Long,
    ): Boolean =
        visitDao.overlapping(spanStartMs, spanEndMs).any { it.startMs < toMs && it.endMs > fromMs }
}
