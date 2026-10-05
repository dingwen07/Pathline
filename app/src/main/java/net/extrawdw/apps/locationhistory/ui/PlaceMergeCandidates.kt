package net.extrawdw.apps.locationhistory.ui

import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.repo.placeMergePairKey

internal data class PlaceMergeCandidate(
    val first: PlaceEntity,
    val second: PlaceEntity,
    val distanceMeters: Double,
) {
    val sameName: Boolean = first.name.trim().equals(second.name.trim(), ignoreCase = true)
}

internal fun placeCenterDistance(first: PlaceEntity, second: PlaceEntity): Double? {
    if (!first.hasCanonicalCenter() || !second.hasCanonicalCenter()) return null
    return Geo.distanceMeters(first.latitude, first.longitude, second.latitude, second.longitude)
        .takeIf { it.isFinite() }
}

/** Latitude sweep avoids comparing distant rows; exact spherical distance handles the date line. */
internal fun findPlaceMergeCandidates(
    places: List<PlaceEntity>,
    ignoredPairs: Set<String> = emptySet(),
): List<PlaceMergeCandidate> {
    val sorted = places.filter { it.hasCanonicalCenter() }.sortedBy { it.latitude }
    val candidates = mutableListOf<PlaceMergeCandidate>()
    for (i in sorted.indices) {
        val first = sorted[i]
        for (j in i + 1 until sorted.size) {
            val second = sorted[j]
            // 0.001 degrees latitude exceeds 100 m everywhere on the spherical distance model.
            if (second.latitude - first.latitude > 0.001) break
            if (placeMergePairKey(first, second) in ignoredPairs) continue
            val distance = placeCenterDistance(first, second) ?: continue
            if (distance <= 100.0) {
                val (a, b) = if (first.id < second.id) first to second else second to first
                candidates += PlaceMergeCandidate(a, b, distance)
            }
        }
    }
    return candidates.sortedWith(compareBy({ it.distanceMeters }, { it.first.id }, { it.second.id }))
}

internal fun rankPlaceMergeTargets(
    source: PlaceEntity,
    places: List<PlaceEntity>,
    query: String,
): List<PlaceEntity> {
    val search = query.trim()
    return places.asSequence()
        .filter { it.id != source.id }
        .filter { search.isEmpty() || it.name.contains(search, ignoreCase = true) ||
            it.address.orEmpty().contains(search, ignoreCase = true) }
        .sortedWith(compareBy<PlaceEntity> { placeCenterDistance(source, it) ?: Double.POSITIVE_INFINITY }
            .thenBy { it.name }.thenBy { it.id })
        .toList()
}

private fun PlaceEntity.hasCanonicalCenter(): Boolean =
    coordinateState == PlaceCoordinateState.WGS84_CANONICAL &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
