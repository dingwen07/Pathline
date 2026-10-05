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

    @Test fun altitudeUsesElapsedTimeAndPreservesMissingReadingsAsGaps() {
        val playback = TripPlayback(listOf(
            point(0, 0.0).copy(altitudeMeters = -10.0),
            point(10_000, 1.0).copy(altitudeMeters = 10.0),
            point(100_000, 2.0).copy(altitudeMeters = 100.0),
            point(110_000, 3.0),
            point(120_000, 4.0).copy(altitudeMeters = 120.0),
            point(130_000, 5.0).copy(altitudeMeters = Double.NaN),
        ))
        assertEquals(50.0, playback.altitudeAt(50_000)!!, 1e-9)
        assertEquals(-10.0, playback.altitudeAt(0)!!, 1e-9)
        assertEquals(listOf(listOf(0L, 10_000L, 100_000L), listOf(120_000L)),
            playback.altitudeSegments.map { segment -> segment.map { it.timestampMs } })
        for (time in listOf(-1L, 105_000L, 110_000L, 115_000L, 125_000L, 130_000L, 140_000L)) {
            assertNull(playback.altitudeAt(time))
        }
        assertTrue(TripPlayback(listOf(point(0, 0.0))).altitudeSegments.isEmpty())
    }

    @Test fun closedLoopHasAnAverageSpeedDespiteZeroDisplacement() {
        val loop = listOf(0.0 to 0.0, 0.0 to 0.001, 0.001 to 0.001, 0.001 to 0.0, 0.0 to 0.0)
        val trip = TripEntity(1, null, null, 0, 600_000, 0, TransportMode.WALKING, 1f,
            Geo.encodePolyline(loop), Geo.pathLengthMeters(loop), true)
        assertEquals(0.7413, trip.averageSpeedMetersPerSecond()!!, 0.001)
        assertNull(trip.copy(endMs = 0).averageSpeedMetersPerSecond())
    }

    private fun point(time: Long, longitude: Double) = TimedRoutePoint(time, Wgs84Coordinate(0.0, longitude))
}
