package net.extrawdw.apps.locationhistory.data.places

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchByTextRequest
import com.google.android.libraries.places.api.net.SearchNearbyRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import net.extrawdw.apps.locationhistory.core.CandidateOrigin
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateAdapter
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateProfile
import net.extrawdw.apps.locationhistory.core.coordinates.GooglePlacesCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.core.coordinates.getOrNull
import net.extrawdw.apps.locationhistory.security.MapsApiKeyVault
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

sealed interface MapsApiKeyTestResult {
    data object Working : MapsApiKeyTestResult
    data object NotConfigured : MapsApiKeyTestResult
    data class Failed(val reason: String) : MapsApiKeyTestResult
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
 * Wraps the Google Places SDK (new). The client is initialized exclusively from the user's
 * Keystore-wrapped key and recreated when that key changes. The bundled Maps SDK key never reaches
 * this class.
 */
@Singleton
class PlacesGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val coordinateAdapter: GoogleAndroidCoordinateAdapter,
    private val keyVault: MapsApiKeyVault,
) : PlacesPort {
    private val clientLock = Any()
    @Volatile private var cachedKeyFingerprint: String? = null
    @Volatile private var cachedClient: PlacesClient? = null

    private fun client(): PlacesClient? {
        val key = keyVault.apiKey() ?: return null
        val fingerprint = keyVault.fingerprint(key)
        cachedClient?.takeIf { cachedKeyFingerprint == fingerprint }?.let { return it }
        return synchronized(clientLock) {
            cachedClient?.takeIf { cachedKeyFingerprint == fingerprint } ?: runCatching {
                if (Places.isInitialized()) Places.deinitialize()
                Places.initializeWithNewPlacesApiEnabled(context.applicationContext, key)
                Places.createClient(context.applicationContext).also {
                    cachedKeyFingerprint = fingerprint
                    cachedClient = it
                }
            }.getOrNull()
        }
    }

    /** ID-only details is the no-charge validation SKU; it does not exercise the Routes API. */
    suspend fun testConfiguredKey(): MapsApiKeyTestResult {
        val placesClient = client() ?: return MapsApiKeyTestResult.NotConfigured
        return try {
            val response = withTimeoutOrNull(8_000) {
                placesClient.fetchPlace(
                    FetchPlaceRequest.newInstance(TEST_PLACE_ID, listOf(Place.Field.ID))
                ).await()
            } ?: return MapsApiKeyTestResult.Failed("Timed out")
            if (response.place.id == TEST_PLACE_ID) MapsApiKeyTestResult.Working
            else MapsApiKeyTestResult.Failed("Unexpected Places response")
        } catch (t: Throwable) {
            MapsApiKeyTestResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** The single most likely POI near canonical [center], or null if none / unavailable. */
    override suspend fun nearestPlace(
        center: Wgs84Coordinate,
        radiusMeters: Double,
    ): PlaceCandidate? = withTimeoutOrNull(4_000) {
        searchNearby(
            center = center,
            radiusMeters = radiusMeters,
            maxResults = 1,
            includeAddress = false,
            rankPreference = SearchNearbyRequest.RankPreference.DISTANCE,
        ).firstOrNull()
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
        val placesClient = client() ?: return emptyList()
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
        return runCatching {
            searchNearby(center, radiusMeters, maxResults = 10, includeAddress = true)
        }.getOrDefault(emptyList())
    }

    private suspend fun searchNearby(
        center: Wgs84Coordinate,
        radiusMeters: Double,
        maxResults: Int,
        includeAddress: Boolean,
        rankPreference: SearchNearbyRequest.RankPreference? = null,
    ): List<PlaceCandidate> {
        val operationProfile = coordinateAdapter.profile
        val providerCenter = coordinateAdapter.toPlacesRequest(center, operationProfile)
            .getOrNull() ?: return emptyList()
        val placesClient = client() ?: return emptyList()
        val fields = buildList {
            add(Place.Field.ID)
            add(Place.Field.DISPLAY_NAME)
            add(Place.Field.LOCATION)
            add(Place.Field.TYPES)
            add(Place.Field.PRIMARY_TYPE)
            if (includeAddress) add(Place.Field.FORMATTED_ADDRESS)
        }
        val bounds = CircularBounds.newInstance(
            LatLng(providerCenter.latitude, providerCenter.longitude),
            radiusMeters,
        )
        val requestBuilder = SearchNearbyRequest.builder(bounds, fields)
            .setMaxResultCount(maxResults)
        if (rankPreference != null) requestBuilder.setRankPreference(rankPreference)
        val request = requestBuilder.build()
        return normalizeAndRankPlaces(
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
    }

    private companion object {
        // Official stable sample from the Places documentation (Empire State Building).
        const val TEST_PLACE_ID = "ChIJaXQRs6lZwokRY6EFpJnhNNE"
    }
}
