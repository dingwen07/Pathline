package net.extrawdw.apps.locationhistory.data.places

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.auth.PlacesAppCheckTokenProvider
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchByTextRequest
import com.google.android.libraries.places.api.net.SearchNearbyRequest
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import net.extrawdw.apps.locationhistory.BuildConfig
import net.extrawdw.apps.locationhistory.core.CandidateOrigin
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateAdapter
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateProfile
import net.extrawdw.apps.locationhistory.core.coordinates.GooglePlacesCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.core.coordinates.getOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** A temporary nearby-place suggestion from Google Places (never persisted until user-confirmed). */
data class PlaceCandidate(
    val name: String,
    val googlePlaceId: String?,
    /** Canonicalized immediately at provider ingress; safe for WGS domain math and persistence. */
    val coordinate: Wgs84Coordinate,
    val address: String?,
    /** The single primary Google type (SDK `primaryType`, falling back to the first of [types]). */
    val primaryType: String?,
    /** The full Google place-type list, in the order Google returned it. */
    val types: List<String> = emptyList(),
    val origin: CandidateOrigin = CandidateOrigin.MAPS,
) {
    val latitude: Double get() = coordinate.latitude
    val longitude: Double get() = coordinate.longitude
}

/** Domain-facing Places contract. Google SDK coordinates never escape its implementation. */
interface PlacesPort {
    suspend fun nearestPlace(
        center: Wgs84Coordinate,
        radiusMeters: Double = 80.0,
    ): PlaceCandidate?

    suspend fun searchText(
        query: String,
        biasCenter: Wgs84Coordinate,
        biasRadiusMeters: Double = 3_000.0,
    ): List<PlaceCandidate>

    suspend fun nearbyPlaces(
        center: Wgs84Coordinate,
        radiusMeters: Double = 120.0,
    ): List<PlaceCandidate>
}

internal data class RankedCanonicalPlace<T>(
    val value: T,
    val coordinate: Wgs84Coordinate,
    val distanceMeters: Double,
)

/** Normalize provider coordinates before distance/ranking; malformed/unverified results drop out. */
internal fun <T> normalizeAndRankPlaces(
    center: Wgs84Coordinate,
    values: List<T>,
    coordinateAdapter: GoogleAndroidCoordinateAdapter,
    operationProfile: GoogleAndroidCoordinateProfile,
    providerCoordinate: (T) -> GooglePlacesCoordinate?,
): List<RankedCanonicalPlace<T>> = values.mapNotNull { value ->
    val provider = providerCoordinate(value) ?: return@mapNotNull null
    val canonical = coordinateAdapter.fromPlacesResult(provider, operationProfile)
        .getOrNull() ?: return@mapNotNull null
    RankedCanonicalPlace(
        value = value,
        coordinate = canonical,
        distanceMeters = Geo.distanceMeters(
            center.latitude,
            center.longitude,
            canonical.latitude,
            canonical.longitude,
        ),
    )
}.sortedBy { it.distanceMeters }

/**
 * Wraps the Google Places SDK (new). Lazily initializes the client from the build's Maps key; if
 * the key is missing or Places is unavailable, every call returns null so the rest of the app
 * keeps working (visits simply stay without a Maps suggestion).
 */
@Singleton
class PlacesGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val coordinateAdapter: GoogleAndroidCoordinateAdapter,
) : PlacesPort {
    private val client: PlacesClient? by lazy { createClient() }

    private fun createClient(): PlacesClient? = runCatching {
        val key = BuildConfig.MAPS_API_KEY
        if (key.isBlank()) return null
        if (!Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(context.applicationContext, key)
        }

        if (FirebaseApp.getApps(context.applicationContext).isNotEmpty()) {
            Places.setPlacesAppCheckTokenProvider(
                PlacesAppCheckTokenProvider {
                    CallbackToFutureAdapter.getFuture { completer ->
                        FirebaseAppCheck.getInstance().getAppCheckToken(false)
                            .addOnSuccessListener { completer.set(it.token) }
                            .addOnFailureListener { completer.setException(it) }
                        "places-app-check-token"
                    }
                },
            )
        }
        Places.createClient(context.applicationContext)
    }.getOrNull()

    /** The single most likely POI near canonical [center], or null if none / unavailable. */
    override suspend fun nearestPlace(
        center: Wgs84Coordinate,
        radiusMeters: Double,
    ): PlaceCandidate? = withTimeoutOrNull(2_000) {
        nearbyPlaces(center, radiusMeters).firstOrNull()
    }

    /** Free-text search focused near canonical [biasCenter] and ranked in that same frame. */
    override suspend fun searchText(
        query: String,
        biasCenter: Wgs84Coordinate,
        biasRadiusMeters: Double,
    ): List<PlaceCandidate> {
        if (query.isBlank()) return emptyList()
        val operationProfile = coordinateAdapter.profile
        val providerCenter = coordinateAdapter.toPlacesRequest(biasCenter, operationProfile)
            .getOrNull() ?: return emptyList()
        val placesClient = client ?: return emptyList()
        return runCatching {
            val fields = listOf(
                Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.FORMATTED_ADDRESS,
                Place.Field.LOCATION, Place.Field.TYPES, Place.Field.PRIMARY_TYPE,
            )
            val request = SearchByTextRequest.builder(query, fields)
                .setLocationBias(
                    CircularBounds.newInstance(
                        LatLng(providerCenter.latitude, providerCenter.longitude),
                        biasRadiusMeters,
                    )
                )
                .setMaxResultCount(10)
                .build()
            normalizeAndRankPlaces(
                center = biasCenter,
                values = placesClient.searchByText(request).await().places,
                coordinateAdapter = coordinateAdapter,
                operationProfile = operationProfile,
                providerCoordinate = { place ->
                    place.location?.let { GooglePlacesCoordinate(it.latitude, it.longitude) }
                },
            ).map { ranked ->
                    val place = ranked.value
                    PlaceCandidate(
                        name = place.displayName ?: place.formattedAddress ?: "Place",
                        googlePlaceId = place.id,
                        coordinate = ranked.coordinate,
                        address = place.formattedAddress,
                        primaryType = place.primaryType ?: place.placeTypes?.firstOrNull(),
                        types = place.placeTypes ?: emptyList(),
                    )
                }
        }.getOrDefault(emptyList())
    }

    /** Nearby POIs around canonical [center], nearest first. Empty if Places is unavailable. */
    override suspend fun nearbyPlaces(
        center: Wgs84Coordinate,
        radiusMeters: Double,
    ): List<PlaceCandidate> {
        val operationProfile = coordinateAdapter.profile
        val providerCenter = coordinateAdapter.toPlacesRequest(center, operationProfile)
            .getOrNull() ?: return emptyList()
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
            val bounds = CircularBounds.newInstance(
                LatLng(providerCenter.latitude, providerCenter.longitude),
                radiusMeters,
            )
            val request = SearchNearbyRequest.builder(bounds, fields)
                .setMaxResultCount(10)
                .build()
            normalizeAndRankPlaces(
                center = center,
                values = placesClient.searchNearby(request).await().places,
                coordinateAdapter = coordinateAdapter,
                operationProfile = operationProfile,
                providerCoordinate = { place ->
                    place.location?.let { GooglePlacesCoordinate(it.latitude, it.longitude) }
                },
            ).map { ranked ->
                    val place = ranked.value
                    PlaceCandidate(
                        name = place.displayName ?: place.formattedAddress ?: "Unknown place",
                        googlePlaceId = place.id,
                        coordinate = ranked.coordinate,
                        address = place.formattedAddress,
                        primaryType = place.primaryType ?: place.placeTypes?.firstOrNull(),
                        types = place.placeTypes ?: emptyList(),
                    )
                }
        }.getOrDefault(emptyList())
    }
}
