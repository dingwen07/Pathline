package net.extrawdw.apps.locationhistory.ui

import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity

internal fun rankDeletionPlaces(
    places: List<PlaceEntity>, sample: LocationSampleEntity?, visit: VisitEntity?,
): List<PlaceEntity> {
    val origin = sample?.let { it.latitude to it.longitude }?.takeIf { validCenter(it) }
        ?: visit?.let { it.centroidLatitude to it.centroidLongitude }?.takeIf { validCenter(it) }
    return places.sortedWith(compareBy<PlaceEntity> {
        if (origin != null && it.coordinateState == PlaceCoordinateState.WGS84_CANONICAL &&
            validCenter(it.latitude to it.longitude))
            Geo.distanceMeters(origin.first, origin.second, it.latitude, it.longitude)
        else Double.POSITIVE_INFINITY
    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })
}

private fun validCenter(point: Pair<Double, Double>) =
    point.first in -90.0..90.0 && point.second in -180.0..180.0

internal fun filterDeletionPlaces(places: List<PlaceEntity>, query: String): List<PlaceEntity> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return places.filter { place ->
        words.all { word -> place.name.contains(word, ignoreCase = true) ||
            place.address.orEmpty().contains(word, ignoreCase = true) }
    }
}
