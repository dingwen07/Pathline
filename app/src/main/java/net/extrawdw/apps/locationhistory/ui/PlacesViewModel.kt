package net.extrawdw.apps.locationhistory.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.places.PlaceCandidate
import net.extrawdw.apps.locationhistory.data.repo.PlaceChoice
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.data.repo.TimelineRepository
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.domain.AnnotationData
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import javax.inject.Inject

@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val timelineRepository: TimelineRepository,
    private val annotationStore: AnnotationStore,
) : ViewModel() {

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

    /** Save user edits to a place. Setting [PlaceEntity.fixed] stops future auto-updates. */
    fun updatePlace(
        place: PlaceEntity,
        name: String,
        address: String?,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        fixed: Boolean,
    ) = viewModelScope.launch {
        placeRepository.update(
            place.copy(
                name = name,
                address = address,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                fixed = fixed,
            ),
        )
    }

    fun deleteIfUnvisited(placeId: Long) = viewModelScope.launch {
        placeRepository.deleteIfUnvisited(placeId)
    }

    // --- annotations (notes / tags / view-only memories) ---------------------------------------

    suspend fun loadAnnotations(target: AnnotationTarget, id: Long): AnnotationData =
        annotationStore.loadEdits(target, id)

    /** Persist edited note + tags. Runs on [viewModelScope] so it survives the editor closing. */
    fun saveAnnotations(target: AnnotationTarget, id: Long, note: String, tags: List<String>) =
        viewModelScope.launch { annotationStore.saveEdits(target, id, note, tags) }
}
