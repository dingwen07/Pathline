package net.extrawdw.apps.locationhistory.ui

import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity

/** Nearest first to the latest recorded location sample. */
internal fun sortSavedPlacesByLatestSample(
    places: List<PlaceEntity>,
    latestSample: LocationSampleEntity?,
): List<PlaceEntity> {
    if (latestSample == null || !validCoordinate(
            latestSample.latitude,
            latestSample.longitude,
        )
    ) return places

    // Only canonical centers can participate in distance math. Keep unresolved places visible
    // after the ranked places, retaining the DAO's visit-count/name order for ties and fallbacks.
    return places.map { place ->
        val distance = if (place.coordinateState == PlaceCoordinateState.WGS84_CANONICAL &&
            validCoordinate(place.latitude, place.longitude)
        ) {
            Geo.distanceMeters(
                latestSample.latitude,
                latestSample.longitude,
                place.latitude,
                place.longitude,
            ).takeIf { it.isFinite() } ?: Double.POSITIVE_INFINITY
        } else {
            Double.POSITIVE_INFINITY
        }
        place to distance
    }.sortedBy { it.second }.map { it.first }
}

private fun validCoordinate(latitude: Double, longitude: Double): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0
