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
 * Accuracy policy (mirrors the recorder's stationary gates): fixes coarser than
 * [Constants.SAMPLE_ACCURACY_GATE_METERS] can neither found a cluster nor shape its geometry, and
 * can never veto it — but a coarse fix *consistent* with the cluster (within the stay radius or
 * its own accuracy of the centroid) SUSTAINS presence. Without this, an evening in Doze — hours of
 * Wi-Fi fixes with a canned 100 m accuracy sitting 6 m from the centroid — looked like an evidence
 * gap, so overnight stays split at the last GPS-quality fix and the same-place merge (capped at
 * [Constants.MAX_EVIDENCE_GAP_MS]) could never rejoin them.
 *
 * Used both to finalize a visit live and to recompute visits from raw history in a worker.
 */
@Singleton
class VisitDetector @Inject constructor() {

    fun detectVisits(samples: List<LocationSampleEntity>): List<VisitCandidate> {
        val usable = samples.filter { it.includedInComputation }
        if (usable.isEmpty()) return emptyList()

        val visits = ArrayList<VisitCandidate>()
        var cluster = ArrayList<LocationSampleEntity>()
        var cLat = 0.0
        var cLon = 0.0
        // Last fix (precise or coarse-sustaining) supporting continued presence at the cluster.
        var lastEvidenceMs = 0L

        fun flush() {
            if (cluster.size >= 2) {
                val start = cluster.first().timestampMs
                val end = maxOf(cluster.last().timestampMs, lastEvidenceMs)
                if (end - start >= Constants.MIN_VISIT_DURATION_MS) {
                    visits.add(
                        VisitCandidate(start, end, cLat, cLon, cluster.size),
                    )
                }
            }
            cluster = ArrayList()
        }

        fun seed(s: LocationSampleEntity) {
            cluster.add(s); cLat = s.latitude; cLon = s.longitude
            lastEvidenceMs = s.timestampMs
        }

        for (s in usable) {
            val precise = (s.accuracy ?: Constants.SAMPLE_ACCURACY_GATE_METERS) <=
                    Constants.SAMPLE_ACCURACY_GATE_METERS
            if (cluster.isEmpty()) {
                if (precise) seed(s)
                continue
            }
            // Evidence-gap policy: beyond MAX_EVIDENCE_GAP_MS with no supporting samples, presence
            // is no longer assumed — even at the same spot, a recording gap closes the cluster, so
            // a 10:00 fix and an 18:00 fix become two candidates, not one fabricated 8h visit.
            if (s.timestampMs - lastEvidenceMs > Constants.MAX_EVIDENCE_GAP_MS) {
                flush()
                if (precise) seed(s)
                continue
            }
            val d = Geo.distanceMeters(cLat, cLon, s.latitude, s.longitude)
            if (!precise) {
                // Sustain-or-ignore: consistent coarse fixes extend the stay's evidence clock but
                // never move the centroid; inconsistent ones are no proof of departure either.
                if (d <= coarseSustainAllowanceMeters(s.accuracy)) lastEvidenceMs = s.timestampMs
                continue
            }
            if (d <= Constants.STATIONARY_RADIUS_METERS) {
                cluster.add(s)
                lastEvidenceMs = s.timestampMs
                // Incremental centroid update.
                cLat += (s.latitude - cLat) / cluster.size
                cLon += (s.longitude - cLon) / cluster.size
            } else {
                flush()
                seed(s)
            }
        }
        flush()
        return visits
    }

    fun centroid(samples: List<LocationSampleEntity>): Pair<Double, Double> =
        Geo.centroid(samples.map { it.latitude to it.longitude })
}

/**
 * Distance within which a coarse fix (accuracy past the 60 m gate) counts as *consistent* with a
 * stay centroid and may sustain its evidence clock: the fix's own accuracy, floored at the stay
 * radius and capped at a plausible place radius so junk accuracy (300-500 m) can't sustain a stay
 * from across the neighborhood. Shared by [VisitDetector], the rebuilder's ongoing-stay tail and
 * the merger's confirmed-pair evidence bridge so the policy can't drift apart.
 */
internal fun coarseSustainAllowanceMeters(accuracyMeters: Float?): Double = maxOf(
    Constants.STATIONARY_RADIUS_METERS,
    (accuracyMeters ?: Constants.SAMPLE_ACCURACY_GATE_METERS).toDouble()
        .coerceAtMost(Constants.PLACE_MAX_RADIUS_METERS),
)
