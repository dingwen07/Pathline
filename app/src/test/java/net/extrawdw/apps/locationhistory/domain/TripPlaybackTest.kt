package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPlaybackTest {
    @Test fun partialLineRetainsVisitedVerticesAndEndsAtElapsedTime() {
        val playback = TripPlayback(listOf(point(0, 0.0), point(10_000, 1.0), point(100_000, 2.0)))
        val half = playback.pathUntil(50_000)
        assertEquals(listOf(0.0, 1.0), half.dropLast(1).map { it.longitude })
        assertEquals(1.0 + 40.0 / 90.0, half.last().longitude, 1e-9)
        // Scrubbing backward truncates the line; no later sample remains visible.
        assertEquals(listOf(0.0, 1.0), playback.pathUntil(10_000).map { it.longitude })
    }

    @Test fun lineStartsEmptyAndFinishesWithEveryRecordedVertex() {
        val playback = TripPlayback(listOf(point(0, 0.0), point(10_000, 1.0), point(100_000, 2.0)))
        assertTrue(playback.pathUntil(-1).isEmpty())
        assertEquals(listOf(0.0), playback.pathUntil(0).map { it.longitude })
        assertEquals(listOf(0.0, 1.0, 2.0), playback.pathUntil(100_000).map { it.longitude })
        assertEquals(playback.pathUntil(100_000), playback.pathUntil(200_000))
        assertTrue(TripPlayback(emptyList()).pathUntil(10_000).isEmpty())
        assertTrue(TripPlayback(listOf(point(0, Double.NaN))).pathUntil(10_000).isEmpty())
    }

    @Test fun crossingDateLineUsesTheShortArc() {
        val playback = TripPlayback(listOf(point(0, 179.0), point(10_000, -179.0)))
        assertEquals(-180.0, playback.pathUntil(5_000).last().longitude, 1e-9)
    }

    @Test fun altitudeInterpolatesMissingReadingsByElapsedTime() {
        val playback = TripPlayback(listOf(
            point(0, 0.0).copy(altitudeMeters = -10.0),
            point(10_000, 1.0).copy(altitudeMeters = 10.0),
            point(40_000, 2.0).copy(altitudeMeters = 100.0),
            point(50_000, 3.0),
            point(60_000, 4.0).copy(altitudeMeters = 120.0),
            point(70_000, 5.0).copy(altitudeMeters = Double.NaN),
        ))
        assertEquals(70.0, playback.altitudeAt(30_000)!!, 1e-9)
        assertEquals(-10.0, playback.altitudeAt(0)!!, 1e-9)
        assertEquals(110.0, playback.altitudeAt(50_000)!!, 1e-9)
        assertEquals(listOf(listOf(0L, 10_000L, 40_000L, 60_000L)),
            playback.altitudeSegments.map { segment -> segment.map { it.timestampMs } })
        for (time in listOf(-1L, 65_000L, 70_000L, 80_000L)) {
            assertNull(playback.altitudeAt(time))
        }
        assertTrue(TripPlayback(listOf(point(0, 0.0))).altitudeSegments.isEmpty())
    }

    @Test fun recordedSpeedInterpolatesMissingReadingsAndPreservesStops() {
        // Deliberately identical coordinates: use the recorded speed, not displacement.
        val playback = TripPlayback(listOf(
            point(0, 0.0).copy(speedMetersPerSecond = 0.0),
            point(10_000, 0.0).copy(speedMetersPerSecond = 2.0),
            point(40_000, 0.0).copy(speedMetersPerSecond = 11.0),
            point(45_000, 0.0),
            point(50_000, 0.0).copy(speedMetersPerSecond = -1.0),
            point(55_000, 0.0).copy(speedMetersPerSecond = Double.POSITIVE_INFINITY),
            point(60_000, 0.0).copy(speedMetersPerSecond = 0.0),
        ))
        assertEquals(8.0, playback.speedAt(30_000)!!, 1e-9)
        assertEquals(0.0, playback.speedAt(0)!!, 1e-9)
        assertEquals(8.25, playback.speedAt(45_000)!!, 1e-9)
        assertEquals(5.5, playback.speedAt(50_000)!!, 1e-9)
        assertEquals(2.75, playback.speedAt(55_000)!!, 1e-9)
        assertEquals(0.0, playback.speedAt(60_000)!!, 1e-9)
        assertEquals(listOf(listOf(0L, 10_000L, 40_000L, 60_000L)),
            playback.speedSegments.map { segment -> segment.map { it.timestampMs } })
        for (time in listOf(-1L, 90_000L)) {
            assertNull(playback.speedAt(time))
        }
    }

    @Test fun interpolationLeavesLongOutagesOpenForEachMeasurement() {
        val playback = TripPlayback(listOf(
            point(0, 0.0).copy(altitudeMeters = 10.0, speedMetersPerSecond = 0.0),
            point(30_000, 1.0).copy(altitudeMeters = 20.0),
            point(60_000, 2.0).copy(altitudeMeters = 30.0, speedMetersPerSecond = 10.0),
            point(152_388, 3.0).copy(altitudeMeters = 40.0, speedMetersPerSecond = 5.0),
        ))
        // A speed outage stays open while altitude readings at most 30s apart join.
        assertNull(playback.speedAt(45_000))
        assertEquals(25.0, playback.altitudeAt(45_000)!!, 1e-9)
        assertEquals(listOf(listOf(0L), listOf(60_000L), listOf(152_388L)),
            playback.speedSegments.map { segment -> segment.map { it.timestampMs } })
        // A 92-second period without any location samples also breaks both profiles.
        assertNull(playback.speedAt(100_000))
        assertNull(playback.altitudeAt(100_000))
        assertEquals(listOf(listOf(0L, 30_000L, 60_000L), listOf(152_388L)),
            playback.altitudeSegments.map { segment -> segment.map { it.timestampMs } })
    }

    @Test fun closedLoopHasAnAverageSpeedDespiteZeroDisplacement() {
        val loop = listOf(0.0 to 0.0, 0.0 to 0.001, 0.001 to 0.001, 0.001 to 0.0, 0.0 to 0.0)
        val trip = TripEntity(1, null, null, 0, 600_000, 0, TransportMode.WALKING, 1f,
            Geo.encodePolyline(loop), Geo.pathLengthMeters(loop), true)
        assertEquals(0.7413, trip.averageSpeedMetersPerSecond()!!, 0.001)
        assertNull(trip.copy(endMs = 0).averageSpeedMetersPerSecond())
    }

    private fun point(time: Long, longitude: Double) =
        TimedRoutePoint(time, Wgs84Coordinate(0.0, longitude), speedMetersPerSecond = null)
}
