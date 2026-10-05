package net.extrawdw.apps.locationhistory.ui

import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DataManagementPlacesTest {
    @Test fun latestSampleTakesPrecedenceAndVisitIsTheFallback() {
        val nearSample = place(1, 10.0)
        val nearVisit = place(2, 20.0)
        val places = listOf(nearVisit, nearSample)
        assertEquals(listOf(nearSample, nearVisit), rankDeletionPlaces(places, sample(10.0), visit(20.0)))
        assertEquals(listOf(nearVisit, nearSample), rankDeletionPlaces(places, null, visit(20.0)))
        assertEquals(listOf(nearVisit, nearSample), rankDeletionPlaces(places, sample(Double.NaN), visit(20.0)))
    }

    @Test fun unresolvedPlacesRemainSelectableAfterKnownDistancesAndSearchKeepsRanking() {
        val near = place(1, 10.0).copy(name = "Cafe", address = "River Road")
        val far = place(2, 11.0).copy(name = "River Cafe")
        val unknown = place(3, 10.0).copy(name = "Cafe River", coordinateState = PlaceCoordinateState.UNKNOWN)
        val unrelated = place(4, 10.1)
        val ranked = rankDeletionPlaces(listOf(unknown, far, unrelated, near), sample(10.0), null)
        assertEquals(listOf(near, unrelated, far, unknown), ranked)
        assertEquals(listOf(near, far, unknown), filterDeletionPlaces(ranked, " cAFE  riVER "))
    }

    @Test fun absentOriginUsesStableNameOrder() {
        val a = place(2, 10.0).copy(name = "alpha")
        val b = place(1, 10.0).copy(name = "Bravo")
        assertEquals(listOf(a, b), rankDeletionPlaces(listOf(b, a), null, null))
    }

    private fun place(id: Long, longitude: Double) = PlaceEntity(id = id, name = "Place $id",
        latitude = 0.0, longitude = longitude, radiusMeters = 30.0, category = null, source = PlaceSource.USER,
        googlePlaceId = null, address = null, confirmed = true, createdAtMs = 0,
        coordinateState = PlaceCoordinateState.WGS84_CANONICAL)

    private fun visit(longitude: Double) = VisitEntity(placeId = null, candidateName = null,
        candidateGooglePlaceId = null, candidateLatitude = null, candidateLongitude = null,
        startMs = 0, endMs = 1, dayEpoch = 0, centroidLatitude = 0.0, centroidLongitude = longitude,
        radiusMeters = 30.0, confirmed = true, confidence = 1f, isOngoing = false)

    private fun sample(longitude: Double) = LocationSampleEntity(timestampMs = 0, dayEpoch = 0,
        latitude = 0.0, longitude = longitude, altitude = null, accuracy = null, verticalAccuracyMeters = null,
        bearing = null, bearingAccuracyDegrees = null, speed = null, speedAccuracyMetersPerSecond = null,
        provider = null, isMock = false, elapsedRealtimeNanos = 0, satelliteCount = null, batteryPct = null,
        isCharging = null, networkTransport = null, networkTypeName = null, cellSignalDbm = null,
        hasCellService = null, wifiSsid = null, wifiBssid = null, screenOn = null, arActivity = null,
        arConfidence = null, devicePhysicalState = DevicePhysicalState.STATIONARY,
        devicePhysicalStateConfidence = 1f)
}
