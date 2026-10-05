package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.data.db.TripEntity

data class TimedRoutePoint(
    val timestampMs: Long,
    val coordinate: Wgs84Coordinate,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Double?,
)

/** Time-based route lookup. Uneven sampling must not change the rate of the progress slider. */
class TripPlayback(points: List<TimedRoutePoint>) {
    private val points = points.filter {
        it.coordinate.latitude.isFinite() && it.coordinate.latitude in -90.0..90.0 &&
            it.coordinate.longitude.isFinite() && it.coordinate.longitude in -180.0..180.0
    }.sortedBy { it.timestampMs }.distinctBy { it.timestampMs }

    val hasPositions: Boolean get() = points.isNotEmpty()

    private val altitude = MeasurementProfile(this.points) { it.validAltitude() }
    private val speed = MeasurementProfile(this.points) { it.validSpeed() }

    /** Brief missing readings are interpolated; longer outages remain gaps in each profile. */
    val altitudeSegments get() = altitude.segments
    val speedSegments get() = speed.segments

    fun altitudeAt(timestampMs: Long): Double? = altitude.valueAt(timestampMs)

    fun speedAt(timestampMs: Long): Double? = speed.valueAt(timestampMs)

    private fun TimedRoutePoint.validAltitude() = altitudeMeters?.takeIf { it.isFinite() }
    private fun TimedRoutePoint.validSpeed() = speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0 }

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

/** Use the same valid neighbors for the drawn line and touch readout, without altering samples. */
private class MeasurementProfile(points: List<TimedRoutePoint>, private val value: (TimedRoutePoint) -> Double?) {
    private val readings = points.filter { value(it) != null }
    val segments: List<List<TimedRoutePoint>> = buildList {
        var segment = mutableListOf<TimedRoutePoint>()
        for (point in readings) {
            if (segment.isNotEmpty() && point.timestampMs - segment.last().timestampMs > MAX_INTERPOLATION_GAP_MS) {
                add(segment)
                segment = mutableListOf()
            }
            segment.add(point)
        }
        if (segment.isNotEmpty()) add(segment)
    }

    fun valueAt(timestampMs: Long): Double? {
        val found = readings.binarySearchBy(timestampMs) { it.timestampMs }
        if (found >= 0) return value(readings[found])
        val next = -found - 1
        if (next == 0 || next == readings.size) return null
        val before = readings[next - 1]
        val after = readings[next]
        val elapsed = after.timestampMs - before.timestampMs
        if (elapsed > MAX_INTERPOLATION_GAP_MS) return null
        val fraction = (timestampMs - before.timestampMs).toDouble() / elapsed
        return value(before)!! * (1 - fraction) + value(after)!! * fraction
    }

    private companion object {
        // A display estimate for brief sensor dropouts, never a reconstruction of a long outage.
        const val MAX_INTERPOLATION_GAP_MS = 30_000L
    }
}

/** Stored distance is cumulative route length, so a closed loop still has a nonzero average. */
fun TripEntity.averageSpeedMetersPerSecond(): Double? {
    val durationMs = endMs - startMs
    if (durationMs <= 0 || !distanceMeters.isFinite() || distanceMeters < 0) return null
    return distanceMeters * 1_000 / durationMs
}
