package net.extrawdw.apps.locationhistory.ui

import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedPlaceOrderingTest {
    @Test
    fun distanceOverridesExistingVisitCountAndNameOrder() {
        val far = place(1, 116.50)
        val near = place(2, 116.401)
        val middle = place(3, 116.42)

        assertEquals(
            listOf(near, middle, far),
            sortSavedPlacesByLatestSample(listOf(far, near, middle), sample(116.40)),
        )
    }

    @Test
    fun latestRecordedSampleIsUsedEvenWhenExcludedFromTimelineComputation() {
        val near = place(1, 116.40)
        val far = place(2, 116.50)
        val latest = sample(116.40).copy(
            includedInComputation = false,
            exclusionReason = "low_accuracy",
        )

        assertEquals(
            listOf(near, far),
            sortSavedPlacesByLatestSample(listOf(far, near), latest),
        )
    }

    @Test
    fun updatedLatestSampleChangesTheOrder() {
        val west = place(1, 116.40)
        val east = place(2, 116.50)
        val places = listOf(west, east)

        assertEquals(listOf(west, east), sortSavedPlacesByLatestSample(places, sample(116.40)))
        assertEquals(listOf(east, west), sortSavedPlacesByLatestSample(places, sample(116.50)))
    }

    @Test
    fun noSampleOrInvalidCoordinatePreservesTheExistingOrder() {
        val places = listOf(place(2, 116.50), place(1, 116.40))

        assertEquals(places, sortSavedPlacesByLatestSample(places, null))
        assertEquals(places, sortSavedPlacesByLatestSample(places, sample(Double.NaN)))
        assertEquals(
            places,
            sortSavedPlacesByLatestSample(places, sample(116.40).copy(latitude = 91.0)),
        )
    }

    @Test
    fun unresolvedAndInvalidPlacesStayVisibleAfterRankedPlaces() {
        val legacy = place(1, 116.40).copy(
            coordinateState = PlaceCoordinateState.LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE,
        )
        val unknown = place(2, 116.40).copy(coordinateState = PlaceCoordinateState.UNKNOWN)
        val invalid = place(3, Double.NaN)
        val far = place(4, 116.50)
        val near = place(5, 116.401)

        assertEquals(
            listOf(near, far, legacy, unknown, invalid),
            sortSavedPlacesByLatestSample(listOf(legacy, far, unknown, invalid, near), sample(116.40)),
        )
    }

    @Test
    fun equalDistancesPreserveTheExistingTieOrder() {
        val first = place(2, 116.401)
        val second = place(1, 116.401)

        assertEquals(
            listOf(first, second),
            sortSavedPlacesByLatestSample(listOf(first, second), sample(116.40)),
        )
    }

    private fun place(id: Long, longitude: Double) = PlaceEntity(
        id = id,
        name = "Place $id",
        latitude = 39.90,
        longitude = longitude,
        radiusMeters = 50.0,
        category = null,
        source = PlaceSource.USER,
        googlePlaceId = null,
        address = null,
        confirmed = true,
        createdAtMs = 0L,
        coordinateState = PlaceCoordinateState.WGS84_CANONICAL,
    )

    private fun sample(longitude: Double) = LocationSampleEntity(
        timestampMs = 200L,
        dayEpoch = 0L,
        latitude = 39.90,
        longitude = longitude,
        altitude = null,
        accuracy = null,
        verticalAccuracyMeters = null,
        bearing = null,
        bearingAccuracyDegrees = null,
        speed = null,
        speedAccuracyMetersPerSecond = null,
        provider = null,
        isMock = false,
        elapsedRealtimeNanos = 0L,
        satelliteCount = null,
        batteryPct = null,
        isCharging = null,
        networkTransport = null,
        networkTypeName = null,
        cellSignalDbm = null,
        hasCellService = null,
        wifiSsid = null,
        wifiBssid = null,
        screenOn = null,
        arActivity = null,
        arConfidence = null,
        devicePhysicalState = DevicePhysicalState.UNKNOWN,
        devicePhysicalStateConfidence = 0f,
    )
}
