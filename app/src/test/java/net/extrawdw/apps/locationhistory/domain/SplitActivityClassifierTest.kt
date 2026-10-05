package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitActivityClassifierTest {
    private val classifier = SplitActivityClassifier(VisitDetector(), HeuristicClassifier())
    private val fallback = SegmentType.Moving(TransportMode.BUS)

    @Test fun detectsStaysAndClassifiesMovement() {
        val before = (0..10).map { sample(it, 0f) }
        val after = (11..21).map { sample(it, 15f) }
        assertEquals(SegmentType.Stationary, classifier.classify(before, fallback))
        assertEquals(SegmentType.Moving(TransportMode.CAR), classifier.classify(after, fallback))
    }

    @Test fun insufficientUsableEvidenceKeepsOriginalActivity() {
        assertEquals(fallback, classifier.classify(emptyList(), fallback))
        assertEquals(fallback, classifier.classify(listOf(sample(0, 0f)), fallback))
        assertEquals(fallback, classifier.classify((0..10).map { sample(it, 0f).copy(includedInComputation = false) }, fallback))
    }

    @Test fun excludedSpeedsCannotTurnAStayIntoMovement() {
        val stationary = (0..10).map { sample(it, 0f) }
        val excluded = (11..25).map { sample(it, 100f).copy(includedInComputation = false) }
        assertEquals(SegmentType.Stationary, classifier.classify(stationary + excluded, fallback))
    }

    private fun sample(index: Int, speed: Float) = LocationSampleEntity(
        timestampMs = index * 60_000L, dayEpoch = 0,
        latitude = 1.3 + index * speed * 60 / 111_000, longitude = 103.8,
        altitude = null, accuracy = 5f, verticalAccuracyMeters = null,
        bearing = null, bearingAccuracyDegrees = null, speed = speed,
        speedAccuracyMetersPerSecond = 0.1f, provider = "fused", isMock = false,
        elapsedRealtimeNanos = 0, satelliteCount = null, batteryPct = null,
        isCharging = null, networkTransport = null, networkTypeName = null,
        cellSignalDbm = null, hasCellService = true, wifiSsid = null, wifiBssid = null,
        screenOn = null, arActivity = null, arConfidence = null,
        devicePhysicalState = DevicePhysicalState.UNKNOWN, devicePhysicalStateConfidence = 0f,
    )
}
