package net.extrawdw.apps.locationhistory.data.places

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchByTextRequest
import com.google.android.libraries.places.api.net.SearchNearbyRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import net.extrawdw.apps.locationhistory.BuildConfig
import net.extrawdw.apps.locationhistory.core.Geo
import javax.inject.Inject
import javax.inject.Singleton

/** A temporary nearby-place suggestion from Google Places (never persisted until user-confirmed). */
data class PlaceCandidate(
    val name: String,
    val googlePlaceId: String?,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    /** The single primary Google type (SDK `primaryType`, falling back to the first of [types]). */
    val primaryType: String?,
    /** The full Google place-type list, in the order Google returned it. */
    val types: List<String> = emptyList(),
)

/**
 * Wraps the Google Places SDK (new). Lazily initializes the client from the build's Maps key; if
 * the key is missing or Places is unavailable, every call returns null so the rest of the app
 * keeps working (visits simply stay without a Maps suggestion).
 */
@Singleton
class PlacesGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val client: PlacesClient? by lazy { createClient() }

    private fun createClient(): PlacesClient? = runCatching {
        val key = BuildConfig.MAPS_API_KEY
        if (key.isBlank()) return null
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(context.applicationContext, key)
        }
        Places.createClient(context.applicationContext)
    }.getOrNull()

    /** The single most likely POI near [lat]/[lon], or null if none / unavailable. */
    suspend fun nearestPlace(
        lat: Double,
        lon: Double,
        radiusMeters: Double = 80.0
    ): PlaceCandidate? =
        withTimeoutOrNull(2_000) { nearbyPlaces(lat, lon, radiusMeters).firstOrNull() }

    /** Free-text place search (Maps API), focused near [lat]/[lon] and ranked by distance from it. */
    suspend fun searchText(
        query: String, lat: Double, lon: Double, biasRadiusMeters: Double = 3_000.0,
    ): List<PlaceCandidate> {
        val placesClient = client ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return runCatching {
            val fields = listOf(
                Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.FORMATTED_ADDRESS,
                Place.Field.LOCATION, Place.Field.TYPES, Place.Field.PRIMARY_TYPE,
            )
            val request = SearchByTextRequest.builder(query, fields)
                .setLocationBias(CircularBounds.newInstance(LatLng(lat, lon), biasRadiusMeters))
                .setMaxResultCount(10)
                .build()
            placesClient.searchByText(request).await().places
                .mapNotNull { place ->
                    val loc = place.location ?: return@mapNotNull null
                    PlaceCandidate(
                        name = place.displayName ?: place.formattedAddress ?: "Place",
                        googlePlaceId = place.id,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        address = place.formattedAddress,
                        primaryType = place.primaryType ?: place.placeTypes?.firstOrNull(),
                        types = place.placeTypes ?: emptyList(),
                    ) to Geo.distanceMeters(lat, lon, loc.latitude, loc.longitude)
                }
                .sortedBy { it.second }
                .map { it.first }
        }.getOrDefault(emptyList())
    }

    /** Nearby POIs around [lat]/[lon], nearest first. Empty if Places is unavailable. */
    suspend fun nearbyPlaces(
        lat: Double, lon: Double, radiusMeters: Double = 120.0,
    ): List<PlaceCandidate> {
        val placesClient = client ?: return emptyList()
        return runCatching {
            val fields = listOf(
                Place.Field.ID,
                Place.Field.DISPLAY_NAME,
                Place.Field.FORMATTED_ADDRESS,
                Place.Field.LOCATION,
                Place.Field.TYPES,
                Place.Field.PRIMARY_TYPE,
            )
            val bounds = CircularBounds.newInstance(LatLng(lat, lon), radiusMeters)
            val request = SearchNearbyRequest.builder(bounds, fields)
                .setMaxResultCount(10)
                .build()
            placesClient.searchNearby(request).await().places
                .mapNotNull { place ->
                    val loc = place.location ?: return@mapNotNull null
                    val candidate = PlaceCandidate(
                        name = place.displayName ?: place.formattedAddress ?: "Unknown place",
                        googlePlaceId = place.id,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        address = place.formattedAddress,
                        primaryType = place.primaryType ?: place.placeTypes?.firstOrNull(),
                        types = place.placeTypes ?: emptyList(),
                    )
                    candidate to Geo.distanceMeters(lat, lon, loc.latitude, loc.longitude)
                }
                .sortedBy { it.second }
                .map { it.first }
        }.getOrDefault(emptyList())
    }
}
