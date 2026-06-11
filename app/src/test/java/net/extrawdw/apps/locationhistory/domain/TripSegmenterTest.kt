package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.TransportClassification
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic-sequence tests for the segmentation pipeline ([segmentSamples], the pure core behind
 * [TripSegmenter]) — backlog #7. The classifier is scripted (median speed -> mode) so the tests
 * pin the *pipeline* invariants: window smoothing, contiguous-run merging, short-run folding,
 * excluded-sample filtering and per-segment re-classification — not any ML behavior.
 */
class TripSegmenterTest {

    private val t0 = 1_750_000_000_000L

    /** Median-speed classifier: fast (> 10 m/s) reads as CAR @0.9, else WALKING @0.6. */
    private val classify: (List<LocationSampleEntity>) -> TransportClassification = { window ->
        val speeds = window.map { it.speed ?: 0f }.sorted()
        val median = speeds[speeds.size / 2]
        if (median > 10f) TransportClassification(TransportMode.CAR, 0.9f)
        else TransportClassification(TransportMode.WALKING, 0.6f)
    }

    /** One fix every 10 s; latitude advances by [latStep] degrees per fix (1e-3 deg ~ 111 m). */
    private fun sample(
        i: Int,
        lat: Double,
        speed: Float,
        included: Boolean = true,
    ) = LocationSampleEntity(
        id = 0, timestampMs = t0 + i * 10_000L, dayEpoch = 0,
        latitude = lat, longitude = 0.0, altitude = null, accuracy = 10f,
        verticalAccuracyMeters = null, bearing = null, bearingAccuracyDegrees = null,
        speed = speed, speedAccuracyMetersPerSecond = null, provider = "fused", isMock = false,
        elapsedRealtimeNanos = 0, satelliteCount = null, batteryPct = null, isCharging = null,
        networkTransport = null, networkTypeName = null, cellSignalDbm = null,
        hasCellService = null, wifiSsid = null, wifiBssid = null, screenOn = null,
        arActivity = null, arConfidence = null,
        devicePhysicalState = DevicePhysicalState.WALKING, devicePhysicalStateConfidence = 1f,
        includedInComputation = included,
    )

    /** [count] fixes continuing from [startLat], each [latStep] degrees apart. */
    private fun run(
        startIndex: Int,
        count: Int,
        startLat: Double,
        latStep: Double,
        speed: Float,
    ): List<LocationSampleEntity> =
        (0 until count).map { k -> sample(startIndex + k, startLat + k * latStep, speed) }

    @Test
    fun uniformWalk_isOneSegmentCoveringAllSamples() {
        val samples = run(0, 12, 0.0, 0.001, speed = 1.2f)
        val segments = segmentSamples(samples, classify)
        val s = segments.single()
        assertEquals(TransportMode.WALKING, s.mode)
        assertEquals(0.6f, s.confidence)
        assertEquals(samples.first().timestampMs, s.startMs)
        assertEquals(samples.last().timestampMs, s.endMs)
        assertTrue(s.distanceMeters > 1_000)
    }

    @Test
    fun walkCarWalk_splitsIntoThreeModeSegments() {
        // 10 slow fixes, 8 fast far-apart fixes (~222 m per gap), 10 slow — every run long enough
        // to survive the short-run fold.
        val walk1 = run(0, 10, 0.0, 0.001, speed = 1.2f)
        val car = run(10, 8, 0.010, 0.002, speed = 20f)
        val walk2 = run(18, 10, 0.026, 0.001, speed = 1.2f)
        val segments = segmentSamples(walk1 + car + walk2, classify)

        assertEquals(
            listOf(TransportMode.WALKING, TransportMode.CAR, TransportMode.WALKING),
            segments.map { it.mode },
        )
        // Segments tile the trip: each starts where the previous ended or later, no overlap gaps
        // in coverage of first/last fixes.
        assertEquals(t0, segments.first().startMs)
        assertEquals(walk2.last().timestampMs, segments.last().endMs)
        for (i in 1 until segments.size) {
            assertTrue(segments[i].startMs > segments[i - 1].startMs)
            assertTrue(segments[i].startMs >= segments[i - 1].endMs)
        }
        assertEquals(0.9f, segments[1].confidence)
    }

    @Test
    fun shortBlip_underMinSegmentDistance_foldsIntoPreviousRun() {
        // A 4-fix fast cluster only ~33 m long: a real mode run distance-wise it is nothing, so it
        // must fold into the preceding walk instead of fragmenting the trip.
        val walk1 = run(0, 10, 0.0, 0.001, speed = 1.2f)
        val blip = run(10, 4, 0.0101, 0.0001, speed = 20f)
        val walk2 = run(14, 10, 0.0105, 0.001, speed = 1.2f)
        val segments = segmentSamples(walk1 + blip + walk2, classify)

        // No CAR segment survives; the fold keeps ranges separate (adjacent same-mode segments are
        // later fused by TimelineMerger), so coverage stays contiguous and all-walking.
        assertTrue(segments.none { it.mode == TransportMode.CAR })
        assertEquals(t0, segments.first().startMs)
        assertEquals(walk2.last().timestampMs, segments.last().endMs)
    }

    @Test
    fun excludedSamples_areIgnored() {
        val usable = run(0, 6, 0.0, 0.001, speed = 1.2f)
        val excluded = (6 until 12).map { sample(it, 1.0, speed = 50f, included = false) }
        val segments = segmentSamples(usable + excluded, classify)
        val s = segments.single()
        assertEquals(TransportMode.WALKING, s.mode)
        assertEquals(usable.last().timestampMs, s.endMs)
    }

    @Test
    fun fewerThanTwoUsableSamples_yieldNothing() {
        assertTrue(segmentSamples(emptyList(), classify).isEmpty())
        assertTrue(segmentSamples(listOf(sample(0, 0.0, 1f)), classify).isEmpty())
        assertTrue(
            segmentSamples(
                listOf(sample(0, 0.0, 1f), sample(1, 0.1, 1f, included = false)),
                classify,
            ).isEmpty(),
        )
    }
}
