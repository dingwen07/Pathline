package net.extrawdw.apps.locationhistory.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.places.PlaceCandidate
import net.extrawdw.apps.locationhistory.data.repo.PlaceChoice
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.data.repo.TimelineRepository
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.domain.AnnotationData
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import net.extrawdw.apps.locationhistory.domain.SegmentType
import net.extrawdw.apps.locationhistory.domain.TimelineDay
import net.extrawdw.apps.locationhistory.domain.TimelineEditor
import net.extrawdw.apps.locationhistory.domain.TimelineItem
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject

/** A polyline segment to draw on the map, coloured by its transport mode. */
data class MapSegment(val points: List<LatLng>, val mode: TransportMode, val confirmed: Boolean)

/** A visit drawn "my-location" style: a small solid dot + a translucent accuracy circle. */
data class MapVisitMarker(val center: LatLng, val radiusMeters: Double, val confirmed: Boolean)

/** A saved place drawn as a translucent yellow radius ring (no dot, no edge). */
data class MapPlaceRing(val center: LatLng, val radiusMeters: Double)

data class MapState(
    val dayEpoch: Long? = null,
    val rawPath: List<LatLng> = emptyList(),
    val segments: List<MapSegment> = emptyList(),
    val visits: List<MapVisitMarker> = emptyList(),
    val placeRings: List<MapPlaceRing> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val timelineRepository: TimelineRepository,
    private val placeRepository: PlaceRepository,
    private val timelineEditor: TimelineEditor,
    private val annotationStore: AnnotationStore,
    private val workScheduler: WorkScheduler,
    private val workManager: WorkManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val today: Long get() = TimeBuckets.dayEpoch(System.currentTimeMillis())
    val selectedDay = MutableStateFlow(today)

    /** Whether the background-recording master switch is on. Drives the Timeline "recording off"
     *  banner. Starts `true` so the banner doesn't flash before the stored setting resolves. */
    val recordingEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.trackingEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val refreshing = MutableStateFlow(false)
    private var lastRefreshMs = 0L
    private var refreshJob: Job? = null

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    val recordedDays: StateFlow<List<Long>> = timelineRepository.observeRecordedDays()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(today))

    val timeline: StateFlow<TimelineDay> = selectedDay
        .flatMapLatest { timelineRepository.observeDay(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TimelineDay(today, emptyList())
        )

    val mapState: StateFlow<MapState> = selectedDay
        .flatMapLatest { day -> timelineRepository.observeDay(day).map { buildMapState(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapState())

    val places = placeRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Never navigate into the future. */
    fun selectDay(dayEpoch: Long) {
        selectedDay.value = dayEpoch.coerceAtMost(today)
    }

    /** Reactive timeline for an arbitrary day (used by the day pager's pages). */
    fun timelineFor(day: Long): kotlinx.coroutines.flow.Flow<TimelineDay> =
        timelineRepository.observeDay(day)

    /** Manual pull-to-refresh: enqueue authoritative maintenance and wait for WorkManager result. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshing.value = true
            val start = System.currentTimeMillis()
            try {
                runCatching {
                    val id = workScheduler.enqueueTimelineMaintenanceNow(
                        selectedDay.value,
                        "pull_refresh"
                    )
                    val finished = withTimeoutOrNull(6_000) {
                        workManager.getWorkInfoByIdFlow(id)
                            .filterNotNull()
                            .first { it.state.isFinished }
                    }
                    if (finished?.state == WorkInfo.State.FAILED) error("Timeline maintenance failed")
                }
                val elapsed = System.currentTimeMillis() - start
                if (elapsed < 450) delay(450 - elapsed)
                lastRefreshMs = System.currentTimeMillis()
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Auto-reconcile when the view is shown, without tying app launch to the pull spinner. */
    fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshMs > STALE_MS) {
            lastRefreshMs = now
            workScheduler.enqueueTimelineMaintenanceNow(selectedDay.value, "timeline_visible")
        }
    }

    fun updatePlace(place: net.extrawdw.apps.locationhistory.data.db.PlaceEntity) =
        viewModelScope.launch {
            placeRepository.update(place)
        }

    // --- confirmations -------------------------------------------------------------------------

    fun confirmVisit(visitId: Long, choice: PlaceChoice) = viewModelScope.launch {
        timelineRepository.confirmVisitPlace(visitId, choice)
    }

    fun confirmTripMode(tripId: Long, mode: TransportMode) = viewModelScope.launch {
        timelineRepository.confirmTripMode(tripId, mode)
    }

    suspend fun nearbySuggestions(lat: Double, lon: Double): List<PlaceCandidate> =
        timelineRepository.nearbyPlaceSuggestions(lat, lon)

    suspend fun searchPlaces(query: String, lat: Double, lon: Double): List<PlaceCandidate> =
        timelineRepository.searchPlaces(query, lat, lon)

    // --- editing -------------------------------------------------------------------------------

    suspend fun samplesFor(item: TimelineItem): List<LocationSampleEntity> =
        timelineEditor.samplesFor(item)

    fun splitItem(item: TimelineItem, index: Int, left: SegmentType, right: SegmentType) =
        viewModelScope.launch {
            timelineEditor.splitItem(item, index, left, right)
        }

    fun convertItem(item: TimelineItem, type: SegmentType) = viewModelScope.launch {
        timelineEditor.convertItemType(item, type)
    }

    // --- annotations (notes / tags / view-only memories) ---------------------------------------

    suspend fun loadAnnotations(target: AnnotationTarget, id: Long): AnnotationData =
        annotationStore.loadEdits(target, id)

    /** Persist edited note + tags. Runs on [viewModelScope] so it survives the editor closing. */
    fun saveAnnotations(target: AnnotationTarget, id: Long, note: String, tags: List<String>) =
        viewModelScope.launch { annotationStore.saveEdits(target, id, note, tags) }

    // --- map ----------------------------------------------------------------------------------

    /** Current device location for initial camera / the GPS recenter button. */
    suspend fun currentLatLng(): LatLng? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            )
                .await()?.let { LatLng(it.latitude, it.longitude) }
        }.getOrNull()
    }

    private fun buildMapState(timeline: TimelineDay): MapState {
        val segments = ArrayList<MapSegment>()
        val visits = ArrayList<MapVisitMarker>()
        val placeRings = LinkedHashMap<Long, MapPlaceRing>() // distinct by place id
        for (item in timeline.items) {
            when (item) {
                is TimelineItem.TripItem -> {
                    val t = item.trip
                    val pts =
                        Geo.decodePolyline(t.encodedPolyline).map { LatLng(it.first, it.second) }
                    if (pts.isNotEmpty()) segments.add(MapSegment(pts, t.mode, t.confirmed))
                }

                is TimelineItem.VisitItem -> {
                    val v = item.visit
                    visits.add(
                        MapVisitMarker(
                            LatLng(v.centroidLatitude, v.centroidLongitude),
                            v.radiusMeters,
                            v.confirmed
                        )
                    )
                    // Yellow ring for the place this visit belongs to (one per place).
                    item.place?.let { p ->
                        placeRings[p.id] =
                            MapPlaceRing(LatLng(p.latitude, p.longitude), p.radiusMeters)
                    }
                }
            }
        }
        val raw = segments.flatMap { it.points }
        return MapState(
            dayEpoch = timeline.dayEpoch,
            rawPath = raw,
            segments = segments,
            visits = visits,
            placeRings = placeRings.values.toList(),
        )
    }

    private companion object {
        const val STALE_MS = 30_000L
    }
}
