package net.extrawdw.apps.locationhistory.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geospatial math helpers. All distances are in meters, all angles in degrees. */
object Geo {

    const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle distance between two coordinates using the haversine formula. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_METERS * (2 * atan2(sqrt(a), sqrt(1 - a)))
    }

    /**
     * A latitude/longitude bounding box that contains every point within [radiusMeters] of the
     * center. Used to pre-filter spatial queries cheaply with an index before the exact
     * haversine check. Returned as (minLat, minLon, maxLat, maxLon).
     */
    fun boundingBox(lat: Double, lon: Double, radiusMeters: Double): DoubleArray {
        val latDelta = Math.toDegrees(radiusMeters / EARTH_RADIUS_METERS)
        val lonDelta = Math.toDegrees(
            radiusMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(lat)))
        )
        return doubleArrayOf(lat - latDelta, lon - lonDelta, lat + latDelta, lon + lonDelta)
    }

    /** Centroid (mean latitude/longitude) of a list of coordinates. */
    fun centroid(points: List<Pair<Double, Double>>): Pair<Double, Double> {
        if (points.isEmpty()) return 0.0 to 0.0
        var sumLat = 0.0
        var sumLon = 0.0
        for ((lat, lon) in points) {
            sumLat += lat
            sumLon += lon
        }
        return (sumLat / points.size) to (sumLon / points.size)
    }

    /** Total path length in meters over an ordered list of coordinates. */
    fun pathLengthMeters(points: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += distanceMeters(
                points[i - 1].first, points[i - 1].second,
                points[i].first, points[i].second,
            )
        }
        return total
    }

    /**
     * Encode an ordered list of coordinates using Google's Encoded Polyline Algorithm so a route
     * can be stored compactly on a [TripSegment] and rendered on the map.
     */
    fun encodePolyline(points: List<Pair<Double, Double>>): String {
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L
        for ((lat, lon) in points) {
            val latE5 = Math.round(lat * 1e5)
            val lonE5 = Math.round(lon * 1e5)
            encodeValue(latE5 - prevLat, sb)
            encodeValue(lonE5 - prevLon, sb)
            prevLat = latE5
            prevLon = lonE5
        }
        return sb.toString()
    }

    /** Decode a polyline produced by [encodePolyline] (or any standard E5 polyline). */
    fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val result = ArrayList<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            lat += decodeValue(encoded, index).also { index = it.nextIndex }.value
            lon += decodeValue(encoded, index).also { index = it.nextIndex }.value
            result.add((lat / 1e5) to (lon / 1e5))
        }
        return result
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }

    private data class Decoded(val value: Long, val nextIndex: Int)

    private fun decodeValue(encoded: String, start: Int): Decoded {
        var index = start
        var shift = 0
        var result = 0L
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
        } while (b >= 0x20)
        val value = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
        return Decoded(value, index)
    }
}
