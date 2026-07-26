package net.extrawdw.apps.locationhistory.data.db

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateRepairDecision
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceCoordinateRepairSerializationTest {
    @Test
    fun backupJsonRoundTripPreservesJournalGeometryExactly() {
        val original = PlaceCoordinateRepairEntity(
            id = 17,
            placeId = 23,
            originalLatitude = 31.229_100_123_456_78,
            originalLongitude = 121.475_900_456_789_01,
            originalRadiusMeters = 57.123_456_789,
            originalAnchorLatitude = 31.228_457_738_123_45,
            originalAnchorLongitude = 121.478_223_059_123_45,
            originalAnchorRadiusMeters = 42.987_654_321,
            originalCoordinateState = PlaceCoordinateState.UNKNOWN,
            originalSource = net.extrawdw.apps.locationhistory.core.PlaceSource.USER,
            repairedLatitude = 31.230_400_123_456_78,
            repairedLongitude = 121.473_700_456_789_01,
            repairedRadiusMeters = 57.123_456_789,
            repairedAnchorLatitude = 31.230_400_123_456_78,
            repairedAnchorLongitude = 121.473_700_456_789_01,
            repairedAnchorRadiusMeters = 42.987_654_321,
            repairedCoordinateState = PlaceCoordinateState.WGS84_CANONICAL,
            repairedSource = net.extrawdw.apps.locationhistory.core.PlaceSource.MAPS,
            decision = PlaceCoordinateRepairDecision
                .GOOGLE_BASELINE_AS_HISTORICAL_PROVIDER,
            profileId = "historical-test-profile",
            repairedAtMs = 1_752_470_400_123,
        )

        val restored = Json.decodeFromString<PlaceCoordinateRepairEntity>(
            Json.encodeToString(original)
        )

        assertEquals(original, restored)
        assertEquals(original.originalLatitude.toRawBits(), restored.originalLatitude.toRawBits())
        assertEquals(
            original.originalLongitude.toRawBits(),
            restored.originalLongitude.toRawBits(),
        )
        assertEquals(original.repairedLatitude.toRawBits(), restored.repairedLatitude.toRawBits())
        assertEquals(
            original.repairedLongitude.toRawBits(),
            restored.repairedLongitude.toRawBits(),
        )
    }
}
