package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.data.db.TripEntity

data class TimedRoutePoint(
    val timestampMs: Long,
    val coordinate: Wgs84Coordinate,
    val altitudeMeters: Double? = null,
)

/** Time-based route lookup. Uneven sampling must not change the rate of the progress slider. */
class TripPlayback(points: List<TimedRoutePoint>) {
    private val points = points.filter {
        it.coordinate.latitude.isFinite() && it.coordinate.latitude in -90.0..90.0 &&
            it.coordinate.longitude.isFinite() && it.coordinate.longitude in -180.0..180.0
    }.sortedBy { it.timestampMs }.distinctBy { it.timestampMs }

    val hasPositions: Boolean get() = points.isNotEmpty()

    /** Missing altitude breaks the profile rather than implying zero or bridging the gap. */
    val altitudeSegments: List<List<TimedRoutePoint>> = buildList {
        var segment = mutableListOf<TimedRoutePoint>()
        for (point in this@TripPlayback.points) {
            if (point.altitudeMeters?.isFinite() == true) {
                segment.add(point)
            } else if (segment.isNotEmpty()) {
                add(segment)
                segment = mutableListOf()
            }
        }
        if (segment.isNotEmpty()) add(segment)
    }

    /** Interpolate only between adjacent altitude readings, never outside the recorded interval. */
    fun altitudeAt(timestampMs: Long): Double? {
        val found = points.binarySearchBy(timestampMs) { it.timestampMs }
        if (found >= 0) return points[found].altitudeMeters?.takeIf { it.isFinite() }
        val next = -found - 1
        if (next == 0 || next == points.size) return null
        val before = points[next - 1]
        val after = points[next]
        val start = before.altitudeMeters?.takeIf { it.isFinite() } ?: return null
        val end = after.altitudeMeters?.takeIf { it.isFinite() } ?: return null
        val fraction = (timestampMs - before.timestampMs).toDouble() / (after.timestampMs - before.timestampMs)
        return start + (end - start) * fraction
    }

    /** Recorded route up to this time, ending at an interpolated point between uneven fixes. */
    fun pathUntil(timestampMs: Long): List<Wgs84Coordinate> {
        val found = points.binarySearchBy(timestampMs) { it.timestampMs }
        val count = if (found >= 0) found + 1 else -found - 1
        return buildList {
            for (index in 0 until count) add(points[index].coordinate)
            if (found < 0 && count > 0 && count < points.size) {
                positionAt(timestampMs)?.let { add(it) }
            }
        }
    }

    fun positionAt(timestampMs: Long): Wgs84Coordinate? {
        if (points.isEmpty()) return null
        val found = points.binarySearchBy(timestampMs) { it.timestampMs }
        if (found >= 0) return points[found].coordinate
        val next = -found - 1
        if (next == 0) return points.first().coordinate
        if (next == points.size) return points.last().coordinate
        val before = points[next - 1]
        val after = points[next]
        val fraction = (timestampMs - before.timestampMs).toDouble() / (after.timestampMs - before.timestampMs)
        // Interpolate along the short longitude arc, including a crossing of the date line.
        val longitudeDelta = ((after.coordinate.longitude - before.coordinate.longitude + 540) % 360) - 180
        val longitude = ((before.coordinate.longitude + longitudeDelta * fraction + 540) % 360) - 180
        return Wgs84Coordinate(
            before.coordinate.latitude + (after.coordinate.latitude - before.coordinate.latitude) * fraction,
            longitude,
        )
    }
}

/** Stored distance is cumulative route length, so a closed loop still has a nonzero average. */
fun TripEntity.averageSpeedMetersPerSecond(): Double? {
    val durationMs = endMs - startMs
    if (durationMs <= 0 || !distanceMeters.isFinite() || distanceMeters < 0) return null
    return distanceMeters * 1_000 / durationMs
}
