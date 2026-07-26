package net.extrawdw.apps.locationhistory.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateRepairDecision
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.places.PlaceCandidate
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.LegacyPlaceCoordinateManager
import net.extrawdw.apps.locationhistory.data.repo.PlaceChoice
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.data.repo.TimelineRepository
import net.extrawdw.apps.locationhistory.domain.AnnotationData
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import javax.inject.Inject

@HiltViewModel
class PlacesViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val placeRepository: PlaceRepository,
    private val timelineRepository: TimelineRepository,
    private val locationRepository: LocationRepository,
    private val annotationStore: AnnotationStore,
    private val mapProjector: GoogleMapProjector,
    private val legacyPlaceCoordinates: LegacyPlaceCoordinateManager,
) : ViewModel() {

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    val places: StateFlow<List<PlaceEntity>> = placeRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live, accurate visit count per place (derived from the visits table). */
    val visitCounts: StateFlow<Map<Long, Int>> = placeRepository.observeVisitCounts()
        .map { list -> list.associate { it.placeId to it.visits } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val unconfirmedVisits: StateFlow<List<VisitEntity>> =
        timelineRepository.observeUnconfirmedVisits()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun confirmVisit(visitId: Long, choice: PlaceChoice) = viewModelScope.launch {
        timelineRepository.confirmVisitPlace(visitId, choice)
    }

    suspend fun nearbySuggestions(lat: Double, lon: Double): List<PlaceCandidate> =
        timelineRepository.nearbyPlaceSuggestions(lat, lon)

    suspend fun searchPlaces(query: String, lat: Double, lon: Double): List<PlaceCandidate> =
        timelineRepository.searchPlaces(query, lat, lon)

    suspend fun placeSearchAnchor(): Wgs84Coordinate? =
        currentLatLng() ?: locationRepository.mostRecent()
            ?.let { Wgs84Coordinate(it.latitude, it.longitude) }

    fun addGooglePlace(candidate: PlaceCandidate) = viewModelScope.launch {
        placeRepository.confirmPlace(
            name = candidate.name,
            latitude = candidate.latitude,
            longitude = candidate.longitude,
            googlePlaceId = candidate.googlePlaceId,
            address = candidate.address,
            category = candidate.primaryType,
            types = candidate.types.joinToString(",").ifEmpty { null },
            source = PlaceSource.MAPS,
        )
    }

    /** Save user edits to a place. Setting [PlaceEntity.fixed] stops future auto-updates. */
    fun updatePlace(
        place: PlaceEntity,
        name: String,
        address: String?,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        fixed: Boolean,
        centerChanged: Boolean,
        radiusChanged: Boolean,
    ) = viewModelScope.launch {
        placeRepository.updateFromEditor(
            place,
            name,
            address,
            latitude,
            longitude,
            radiusMeters,
            fixed,
            centerChanged,
            radiusChanged,
        )
    }

    fun projectPlaceForMap(place: PlaceEntity): ProjectedPlaceCircle? =
        mapProjector.placePreviewCircle(place)

    fun projectCoordinateForMap(value: Wgs84Coordinate): GoogleMapCoordinate? =
        mapProjector.coordinate(value)

    fun normalizeMapInteraction(value: GoogleMapCoordinate): Wgs84Coordinate? =
        mapProjector.fromMap(value)

    suspend fun canUndoCoordinateRepair(placeId: Long): Boolean =
        legacyPlaceCoordinates.hasUndo(placeId)

    suspend fun repairCoordinates(
        place: PlaceEntity,
        decision: PlaceCoordinateRepairDecision,
    ): Boolean = legacyPlaceCoordinates.repair(place, decision)

    suspend fun undoCoordinateRepair(place: PlaceEntity): Boolean =
        legacyPlaceCoordinates.undo(place)

    fun deleteIfUnvisited(placeId: Long) = viewModelScope.launch {
        placeRepository.deleteIfUnvisited(placeId)
    }

    // --- annotations (notes / tags / view-only memories) ---------------------------------------

    suspend fun loadAnnotations(target: AnnotationTarget, id: Long): AnnotationData =
        annotationStore.loadEdits(target, id)

    /** Persist edited note + tags. Runs on [viewModelScope] so it survives the editor closing. */
    fun saveAnnotations(target: AnnotationTarget, id: Long, note: String, tags: List<String>) =
        viewModelScope.launch { annotationStore.saveEdits(target, id, note, tags) }

    private suspend fun currentLatLng(): Wgs84Coordinate? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).await()?.let { Wgs84Coordinate(it.latitude, it.longitude) }
        }.getOrNull()
    }
}
