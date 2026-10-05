package net.extrawdw.apps.locationhistory.backup

import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class ActivityGpxExportTest {
    @Test fun activityExportContainsOnlyItsUsableFixesInTimeOrderWithOriginalCoordinates() {
        val trip = TripEntity(
            id = 1, fromVisitId = null, toVisitId = null, startMs = 1_000, endMs = 2_000,
            dayEpoch = 0, mode = TransportMode.WALKING, modeConfidence = 1f,
            encodedPolyline = "", distanceMeters = 50.0, confirmed = false,
        )
        val samples = listOf(
            sample(2_000), sample(999), sample(1_000), sample(2_001),
            sample(1_500).copy(includedInComputation = false),
            sample(1_600).copy(latitude = Double.NaN), sample(1_700).copy(longitude = 190.0),
        )
        val out = ByteArrayOutputStream()
        GpxExporter.write(GpxExporter.tripSamples(trip, samples), out)
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(out.toByteArray().inputStream())
        val points = document.getElementsByTagName("trkpt")
        assertEquals(2, points.length)
        assertEquals("1.3", points.item(0).attributes.getNamedItem("lat").nodeValue)
        assertEquals("103.8", points.item(0).attributes.getNamedItem("lon").nodeValue)
        assertEquals("1970-01-01T00:00:01Z", document.getElementsByTagName("time").item(0).textContent)
        assertEquals("1970-01-01T00:00:02Z", document.getElementsByTagName("time").item(1).textContent)
    }

    private fun sample(time: Long) = LocationSampleEntity(
        timestampMs = time, dayEpoch = 0, latitude = 1.3, longitude = 103.8,
        altitude = 15.0, accuracy = 5f, verticalAccuracyMeters = null,
        bearing = null, bearingAccuracyDegrees = null, speed = 1.5f,
        speedAccuracyMetersPerSecond = null, provider = "fused", isMock = false,
        elapsedRealtimeNanos = 0, satelliteCount = null, batteryPct = null,
        isCharging = null, networkTransport = null, networkTypeName = null,
        cellSignalDbm = null, hasCellService = true, wifiSsid = null, wifiBssid = null,
        screenOn = null, arActivity = null, arConfidence = null,
        devicePhysicalState = DevicePhysicalState.UNKNOWN, devicePhysicalStateConfidence = 0f,
    )
}
