package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A computed center + radius for a stay, plus a normalized [reliability] in [0,1] expressing how
 * trustworthy the geometry is (see [VisitGeometry.reliabilityOf]).
 */
data class StayGeometry(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val reliability: Double = 0.0,
)

/**
 * Computes the center and radius of a stay from the location samples assigned to it, weighting by
 * GPS accuracy. The center is an inverse-variance weighted mean; the radius is the 68th-percentile
 * spread of fixes, floored at the typical accuracy, and clamped — this is what places and visits
 * draw as a dot + circle, and what the place model stores. Shared by [PlaceRepository] and the map.
 *
 * Also derives a [StayGeometry.reliability] score the place model uses to weight this visit.
 */
object VisitGeometry {

    fun compute(
        samples: List<LocationSampleEntity>,
        fallbackLat: Double,
        fallbackLon: Double,
    ): StayGeometry {
        val good = samples
            .filter { (it.accuracy ?: Float.MAX_VALUE) <= Constants.SAMPLE_ACCURACY_GATE_METERS }
            .ifEmpty { samples }
        if (good.isEmpty()) {
            return StayGeometry(fallbackLat, fallbackLon, Constants.PLACE_MIN_RADIUS_METERS, reliability = 0.0)
        }
        val (lat, lon) = accuracyWeightedCentroid(good)
        val spread = percentileDistance(good, lat, lon, 0.68)
        val typicalAccuracy = median(good.mapNotNull { it.accuracy?.toDouble() }) ?: 30.0
        val radius = maxOf(spread, typicalAccuracy)
            .coerceIn(Constants.PLACE_MIN_RADIUS_METERS, Constants.PLACE_MAX_RADIUS_METERS)
        return StayGeometry(lat, lon, radius, reliabilityOf(good, lat, lon, typicalAccuracy))
    }

    /**
     * How much to trust a visit's geometry, in [0,1]. It's the **geometric mean of four independent,
     * saturating sub-scores**, so each is naturally normalized, the weakest factor dominates (a
     * one-sample burst can't look reliable just because it's precise), and the result is itself a
     * clean [0,1] weight the place model can multiply in directly:
     *
     *  - **count** — `n / (n + Nref)`: more fixes ==> more evidence.
     *  - **accuracy** — `Aref / (Aref + medianAccuracy)`: sharper GPS ==> more trust.
     *  - **dispersion** — `Sref / (Sref + σ)`, σ = RMS distance of fixes from the centroid (the
     *    spatial standard deviation): a tight cluster ==> a precise center.
     *  - **duration** — `d / (d + Dref)`: a longer stay ==> more certainly a real visit, not a pass-by.
     *
     * (count × dispersion together approximate the standard error of the mean, σ/√n — the textbook
     * precision of the centroid — while accuracy and duration add independent evidence.)
     */
    fun reliabilityOf(
        samples: List<LocationSampleEntity>,
        centroidLat: Double,
        centroidLon: Double,
        medianAccuracy: Double,
    ): Double {
        if (samples.isEmpty()) return 0.0
        val n = samples.size.toDouble()
        val sigma = rmsDistance(samples, centroidLat, centroidLon)
        val durationMs = (samples.maxOf { it.timestampMs } - samples.minOf { it.timestampMs })
            .coerceAtLeast(0L).toDouble()

        val countScore = n / (n + Constants.VISIT_RELIABILITY_COUNT_REF)
        val accuracyScore = Constants.VISIT_RELIABILITY_ACCURACY_REF_M /
            (Constants.VISIT_RELIABILITY_ACCURACY_REF_M + medianAccuracy.coerceAtLeast(0.0))
        val dispersionScore = Constants.VISIT_RELIABILITY_DISPERSION_REF_M /
            (Constants.VISIT_RELIABILITY_DISPERSION_REF_M + sigma)
        val durationScore = durationMs / (durationMs + Constants.VISIT_RELIABILITY_DURATION_REF_MS)

        return (countScore * accuracyScore * dispersionScore * durationScore)
            .pow(0.25)
            .coerceIn(0.0, 1.0)
    }

    /** RMS distance of fixes from the centroid — the spatial standard deviation, in meters. */
    private fun rmsDistance(samples: List<LocationSampleEntity>, lat: Double, lon: Double): Double {
        if (samples.isEmpty()) return 0.0
        val sumSq = samples.sumOf {
            val d = Geo.distanceMeters(lat, lon, it.latitude, it.longitude); d * d
        }
        return sqrt(sumSq / samples.size)
    }

    fun accuracyWeightedCentroid(samples: List<LocationSampleEntity>): Pair<Double, Double> {
        var sumW = 0.0; var sumLat = 0.0; var sumLon = 0.0
        for (s in samples) {
            val acc = (s.accuracy ?: 30f).coerceAtLeast(5f)
            val w = 1.0 / (acc * acc)
            sumW += w; sumLat += w * s.latitude; sumLon += w * s.longitude
        }
        if (sumW == 0.0) return Geo.centroid(samples.map { it.latitude to it.longitude })
        return (sumLat / sumW) to (sumLon / sumW)
    }

    private fun percentileDistance(
        samples: List<LocationSampleEntity>, lat: Double, lon: Double, p: Double,
    ): Double {
        val dists = samples.map { Geo.distanceMeters(lat, lon, it.latitude, it.longitude) }.sorted()
        if (dists.isEmpty()) return Constants.PLACE_MIN_RADIUS_METERS
        return dists[((dists.size - 1) * p).toInt().coerceIn(0, dists.size - 1)]
    }

    private fun median(values: List<Double>): Double? =
        if (values.isEmpty()) null else values.sorted()[values.size / 2]
}
