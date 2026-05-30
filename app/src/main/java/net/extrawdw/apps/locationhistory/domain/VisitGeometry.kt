package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity

/** A computed center + radius for a stay, derived from its samples and their GPS accuracy. */
data class StayGeometry(val latitude: Double, val longitude: Double, val radiusMeters: Double)

/**
 * Computes the center and radius of a stay from the location samples assigned to it, weighting by
 * GPS accuracy. The center is an inverse-variance weighted mean; the radius is the 68th-percentile
 * spread of fixes, floored at the typical accuracy, and clamped — this is what places and visits
 * draw as a dot + circle, and what the place model stores. Shared by [PlaceRepository] and the map.
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
            return StayGeometry(fallbackLat, fallbackLon, Constants.PLACE_MIN_RADIUS_METERS)
        }
        val (lat, lon) = accuracyWeightedCentroid(good)
        val spread = percentileDistance(good, lat, lon, 0.68)
        val typicalAccuracy = median(good.mapNotNull { it.accuracy?.toDouble() }) ?: 30.0
        val radius = maxOf(spread, typicalAccuracy)
            .coerceIn(Constants.PLACE_MIN_RADIUS_METERS, Constants.PLACE_MAX_RADIUS_METERS)
        return StayGeometry(lat, lon, radius)
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
