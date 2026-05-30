package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import javax.inject.Inject
import javax.inject.Singleton

/** A detected stationary stay, before it is matched to a place. */
data class VisitCandidate(
    val startMs: Long,
    val endMs: Long,
    val centroidLatitude: Double,
    val centroidLongitude: Double,
    val sampleCount: Int,
) {
    val dayEpoch: Long get() = TimeBuckets.dayEpoch(startMs)
    val durationMs: Long get() = endMs - startMs
}

/**
 * Groups a time-ordered sequence of samples into stationary visits. A run of samples that stays
 * within [Constants.STATIONARY_RADIUS_METERS] of its growing centroid for at least
 * [Constants.MIN_VISIT_DURATION_MS] becomes a visit; everything between visits is movement.
 *
 * Used both to finalize a visit live and to recompute visits from raw history in a worker.
 */
@Singleton
class VisitDetector @Inject constructor() {

    fun detectVisits(samples: List<LocationSampleEntity>): List<VisitCandidate> {
        val usable = samples
            .filter { it.includedInComputation }
            .filter { (it.accuracy ?: Constants.SAMPLE_ACCURACY_GATE_METERS) <= Constants.SAMPLE_ACCURACY_GATE_METERS }
        if (usable.isEmpty()) return emptyList()

        val visits = ArrayList<VisitCandidate>()
        var cluster = ArrayList<LocationSampleEntity>()
        var cLat = 0.0
        var cLon = 0.0

        fun flush() {
            if (cluster.size >= 2) {
                val start = cluster.first().timestampMs
                val end = cluster.last().timestampMs
                if (end - start >= Constants.MIN_VISIT_DURATION_MS) {
                    visits.add(
                        VisitCandidate(start, end, cLat, cLon, cluster.size),
                    )
                }
            }
            cluster = ArrayList()
        }

        for (s in usable) {
            if (cluster.isEmpty()) {
                cluster.add(s); cLat = s.latitude; cLon = s.longitude
                continue
            }
            val d = Geo.distanceMeters(cLat, cLon, s.latitude, s.longitude)
            if (d <= Constants.STATIONARY_RADIUS_METERS) {
                cluster.add(s)
                // Incremental centroid update.
                cLat += (s.latitude - cLat) / cluster.size
                cLon += (s.longitude - cLon) / cluster.size
            } else {
                flush()
                cluster.add(s); cLat = s.latitude; cLon = s.longitude
            }
        }
        flush()
        return visits
    }

    fun centroid(samples: List<LocationSampleEntity>): Pair<Double, Double> =
        Geo.centroid(samples.map { it.latitude to it.longitude })
}
