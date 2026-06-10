package net.extrawdw.apps.locationhistory.api

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure helpers for the data API's proximity mode ([PathlineContract.QueryParams.NEAR]): parsing the
 * caller's `lat,lng`, the SQL bounding-box prefilter, and the exact Haversine distance. Free of
 * Android types so the geometry is JVM unit-testable.
 *
 * Design (deliberate): **no caching, no spatial index.** Distance is a function of the query point,
 * so there is nothing reusable to cache, and the saved-place corpus is small — a bounding-box
 * `BETWEEN` prefilter plus Haversine over the survivors is microseconds at any realistic size.
 */
internal object GeoSearch {

    /** Mean Earth radius, meters (IUGG). */
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Meters per degree of latitude (and of longitude at the equator). */
    private const val METERS_PER_DEGREE = 111_320.0

    /** Radius applied when [PathlineContract.QueryParams.RADIUS_M] is omitted. */
    const val DEFAULT_RADIUS_M = 500.0

    /** Hard cap on the requested radius — larger asks clamp, they don't error. */
    const val MAX_RADIUS_M = 50_000.0

    /**
     * The `lat,lng` value of a `near` parameter, validated. Throws [IllegalArgumentException] on
     * anything but two finite numbers in range (lat [-90,90], lng [-180,180]).
     */
    fun parseNear(raw: String): Pair<Double, Double> {
        val parts = raw.split(',').map { it.trim() }
        require(parts.size == 2) { "'near' must be \"lat,lng\" (got '$raw')" }
        val lat = parts[0].toDoubleOrNull()
        val lng = parts[1].toDoubleOrNull()
        require(lat != null && lat.isFinite() && lat in -90.0..90.0) {
            "'near' latitude must be a number in [-90, 90] (got '${parts[0]}')"
        }
        require(lng != null && lng.isFinite() && lng in -180.0..180.0) {
            "'near' longitude must be a number in [-180, 180] (got '${parts[1]}')"
        }
        return lat to lng
    }

    /** The validated radius: [DEFAULT_RADIUS_M] when absent, else a positive number clamped to
     *  [MAX_RADIUS_M]. Throws on a non-positive or non-numeric value. */
    fun parseRadius(raw: String?): Double {
        if (raw == null) return DEFAULT_RADIUS_M
        val r = raw.toDoubleOrNull()
        require(r != null && r.isFinite() && r > 0) {
            "'radius_m' must be a positive number (got '$raw')"
        }
        return r.coerceAtMost(MAX_RADIUS_M)
    }

    /**
     * The lat/lng box that fully contains the [radiusM] circle around the point — the indexed SQL
     * prefilter ran before exact distances. The longitude half-width grows by `1/cos(lat)` toward
     * the poles (cos clamped so the box stays finite). [wrapsAntimeridian] is true when the box
     * crosses the +-180 meridian (or spans all longitudes near a pole) — a single `BETWEEN` can't
     * express that, so the caller falls back to scanning all rows; correctness over cleverness for
     * a case this rare.
     */
    data class BoundingBox(
        val latMin: Double,
        val latMax: Double,
        val lngMin: Double,
        val lngMax: Double,
        val wrapsAntimeridian: Boolean,
    )

    fun boundingBox(lat: Double, lng: Double, radiusM: Double): BoundingBox {
        val halfLat = radiusM / METERS_PER_DEGREE
        val halfLng = halfLat / max(cos(lat.toRadians()), 0.01)
        val lngMin = lng - halfLng
        val lngMax = lng + halfLng
        return BoundingBox(
            latMin = (lat - halfLat).coerceAtLeast(-90.0),
            latMax = (lat + halfLat).coerceAtMost(90.0),
            lngMin = lngMin,
            lngMax = lngMax,
            wrapsAntimeridian = lngMin < -180.0 || lngMax > 180.0,
        )
    }

    /** Great-circle distance between two points, meters (Haversine). */
    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1).toRadians()
        val dLng = (lng2 - lng1).toRadians()
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun Double.toRadians(): Double = Math.toRadians(this)
}
