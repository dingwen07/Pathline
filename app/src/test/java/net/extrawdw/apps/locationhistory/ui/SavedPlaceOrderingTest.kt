package net.extrawdw.apps.locationhistory.ui

import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
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
            sortSavedPlacesByLatestVisit(listOf(far, near, middle), visit(116.40)),
        )
    }

    @Test
    fun unconfirmedOngoingVisitUsesRecordedCentroidRatherThanItsPlacesCandidate() {
        val nearCentroid = place(1, 116.40)
        val nearCandidate = place(2, 116.50)
        val latest = visit(116.40).copy(
            placeId = null,
            confirmed = false,
            isOngoing = true,
            candidateLatitude = 39.90,
            candidateLongitude = 116.50,
        )

        assertEquals(
            listOf(nearCentroid, nearCandidate),
            sortSavedPlacesByLatestVisit(listOf(nearCandidate, nearCentroid), latest),
        )
    }

    @Test
    fun updatedLatestVisitChangesTheOrder() {
        val west = place(1, 116.40)
        val east = place(2, 116.50)
        val places = listOf(west, east)

        assertEquals(listOf(west, east), sortSavedPlacesByLatestVisit(places, visit(116.40)))
        assertEquals(listOf(east, west), sortSavedPlacesByLatestVisit(places, visit(116.50)))
    }

    @Test
    fun noVisitOrInvalidCentroidPreservesTheExistingOrder() {
        val places = listOf(place(2, 116.50), place(1, 116.40))

        assertEquals(places, sortSavedPlacesByLatestVisit(places, null))
        assertEquals(places, sortSavedPlacesByLatestVisit(places, visit(Double.NaN)))
        assertEquals(
            places,
            sortSavedPlacesByLatestVisit(places, visit(116.40).copy(centroidLatitude = 91.0)),
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
            sortSavedPlacesByLatestVisit(listOf(legacy, far, unknown, invalid, near), visit(116.40)),
        )
    }

    @Test
    fun equalDistancesPreserveTheExistingTieOrder() {
        val first = place(2, 116.401)
        val second = place(1, 116.401)

        assertEquals(
            listOf(first, second),
            sortSavedPlacesByLatestVisit(listOf(first, second), visit(116.40)),
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

    private fun visit(longitude: Double) = VisitEntity(
        id = 1L,
        placeId = null,
        candidateName = null,
        candidateGooglePlaceId = null,
        candidateLatitude = null,
        candidateLongitude = null,
        startMs = 100L,
        endMs = 200L,
        dayEpoch = 0L,
        centroidLatitude = 39.90,
        centroidLongitude = longitude,
        radiusMeters = 30.0,
        confirmed = true,
        confidence = 1f,
        isOngoing = false,
    )
}
