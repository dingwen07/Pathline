package net.extrawdw.apps.locationhistory.api

import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiPlaceGeometryTest {
    @Test
    fun canonicalPlace_exposesGeometry() {
        val geometry = place(PlaceCoordinateState.WGS84_CANONICAL).apiGeometry()

        assertEquals(30.29, geometry.latitude!!, 0.0)
        assertEquals(120.12, geometry.longitude!!, 0.0)
        assertEquals(50.0, geometry.radiusMeters!!, 0.0)
    }

    @Test
    fun unresolvedPlace_preservesIdentityButRedactsGeometry() {
        val place = place(PlaceCoordinateState.UNKNOWN)
        val geometry = place.apiGeometry()

        assertEquals(42L, place.id)
        assertEquals("Legacy place", place.name)
        assertNull(geometry.latitude)
        assertNull(geometry.longitude)
        assertNull(geometry.radiusMeters)
    }

    private fun place(state: PlaceCoordinateState) = PlaceEntity(
        id = 42,
        name = "Legacy place",
        latitude = 30.29,
        longitude = 120.12,
        radiusMeters = 50.0,
        category = null,
        source = PlaceSource.MAPS,
        googlePlaceId = "google-id",
        address = "Address",
        confirmed = true,
        createdAtMs = 0L,
        anchorLatitude = 30.29,
        anchorLongitude = 120.12,
        anchorRadiusMeters = 50.0,
        coordinateState = state,
    )
}
