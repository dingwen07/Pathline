package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.places.PlaceCandidate
import net.extrawdw.apps.locationhistory.data.places.PlacesGateway
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of matching a visit centroid to a place. */
sealed interface PlaceMatch {
    /** Matched an existing local place. Confirmed places yield high confidence. */
    data class Local(val place: PlaceEntity, val confidence: Float) : PlaceMatch

    /** No local match; a temporary Google Places suggestion (not yet added to the place DB). */
    data class Candidate(val candidate: PlaceCandidate, val confidence: Float) : PlaceMatch

    /** Nothing matched and Places returned nothing. */
    data object None : PlaceMatch
}

/**
 * Resolves which place a visit happened at. First it checks the **local place database** (cheap,
 * offline, indexed bounding-box pre-filter then exact distance); a confirmed local place is treated
 * as near-certain. If nothing local matches, it asks Google Places for the nearest POI and returns
 * it as a *temporary candidate* — it is NOT written to the place DB until the user confirms it.
 */
@Singleton
class PlaceMatcher @Inject constructor(
    private val placeDao: PlaceDao,
    private val placesGateway: PlacesGateway,
) {

    suspend fun match(lat: Double, lon: Double): PlaceMatch {
        nearestLocalPlace(lat, lon)?.let { (place, distance) ->
            // Confidence falls off with distance within the match radius; confirmed places get a
            // floor so a known home/work is trusted even with sloppy GPS.
            val proximity = (1.0 - distance / Constants.PLACE_MATCH_RADIUS_METERS)
                .coerceIn(0.0, 1.0)
            val confidence = if (place.confirmed) (0.85 + 0.15 * proximity) else (0.5 * proximity)
            return PlaceMatch.Local(place, confidence.toFloat())
        }

        val candidate = placesGateway.nearestPlace(lat, lon, Constants.PLACE_MATCH_RADIUS_METERS)
        return if (candidate != null) {
            PlaceMatch.Candidate(candidate, confidence = 0.5f) // unconfirmed by definition
        } else {
            PlaceMatch.None
        }
    }

    /** @return the nearest local place within the match radius and its distance, or null. */
    private suspend fun nearestLocalPlace(lat: Double, lon: Double): Pair<PlaceEntity, Double>? {
        val box = Geo.boundingBox(lat, lon, Constants.PLACE_MATCH_RADIUS_METERS)
        return placeDao.inBoundingBox(box[0], box[1], box[2], box[3])
            .map { it to Geo.distanceMeters(lat, lon, it.latitude, it.longitude) }
            .filter {
                it.second <= maxOf(
                    Constants.PLACE_MATCH_RADIUS_METERS,
                    it.first.radiusMeters
                )
            }
            .minByOrNull { it.second }
    }
}
