package net.extrawdw.apps.locationhistory.api

import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity

/** Geometry exposed by the data API; unresolved raw coordinates never cross the provider boundary. */
internal data class ApiPlaceGeometry(
    val latitude: Double?,
    val longitude: Double?,
    val radiusMeters: Double?,
)

internal fun PlaceEntity.apiGeometry(): ApiPlaceGeometry =
    if (coordinateState == PlaceCoordinateState.WGS84_CANONICAL) {
        ApiPlaceGeometry(latitude, longitude, radiusMeters)
    } else {
        ApiPlaceGeometry(latitude = null, longitude = null, radiusMeters = null)
    }
