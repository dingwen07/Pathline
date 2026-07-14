package net.extrawdw.apps.locationhistory.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.places.PlaceCandidate
import net.extrawdw.apps.locationhistory.data.repo.PlaceChoice
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.data.repo.TimelineRepository
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateRepairDecision
import net.extrawdw.apps.locationhistory.domain.AnnotationData
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import net.extrawdw.apps.locationhistory.domain.SegmentType
import net.extrawdw.apps.locationhistory.domain.TimelineDay
import net.extrawdw.apps.locationhistory.domain.TimelineEditor
import net.extrawdw.apps.locationhistory.domain.TimelineItem
import net.extrawdw.apps.locationhistory.data.repo.LegacyPlaceCoordinateManager
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject

/** A polyline segment to draw on the map, coloured by its transport mode. */
data class MapSegment(
    val points: List<GoogleMapCoordinate>,
    val mode: TransportMode,
    val confirmed: Boolean,
)

/** A visit drawn "my-location" style: a small solid dot + a translucent accuracy circle. */
data class MapVisitMarker(
    val center: GoogleMapCoordinate,
    val boundsPoints: List<GoogleMapCoordinate>,
    val radiusMeters: Double,
    val confirmed: Boolean,
)

/** A saved place drawn as a translucent yellow radius ring (no dot, no edge). */
data class MapPlaceRing(
    val center: GoogleMapCoordinate,
    val boundsPoints: List<GoogleMapCoordinate>,
    val radiusMeters: Double,
)

data class MapState(
    val dayEpoch: Long? = null,
    val profileId: String = "",
    val rawPaths: List<List<GoogleMapCoordinate>> = emptyList(),
    val segments: List<MapSegment> = emptyList(),
    val visits: List<MapVisitMarker> = emptyList(),
    val placeRings: List<MapPlaceRing> = emptyList(),
)

data class ProjectedDeviceLocation(
    val coordinate: GoogleMapCoordinate,
    val mainlandCompatibilityActive: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val timelineRepository: TimelineRepository,
    private val locationRepository: LocationRepository,
    private val placeRepository: PlaceRepository,
    private val timelineEditor: TimelineEditor,
    private val annotationStore: AnnotationStore,
    private val workScheduler: WorkScheduler,
    private val workManager: WorkManager,
    private val mapProjector: GoogleMapProjector,
    private val legacyPlaceCoordinates: LegacyPlaceCoordinateManager,
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
        .flatMapLatest { day ->
            timelineRepository.observeDay(day).mapLatest {
                withContext(Dispatchers.Default) { buildMapState(it) }
            }
        }
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

    fun updatePlace(
        place: net.extrawdw.apps.locationhistory.data.db.PlaceEntity,
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
            place, name, address, latitude, longitude, radiusMeters, fixed, centerChanged,
            radiusChanged,
        )
    }

    fun projectPlaceForMap(place: net.extrawdw.apps.locationhistory.data.db.PlaceEntity):
            ProjectedPlaceCircle? = mapProjector.placePreviewCircle(place)

    fun projectCoordinateForMap(value: Wgs84Coordinate): GoogleMapCoordinate? =
        mapProjector.coordinate(value)

    fun normalizeMapInteraction(value: GoogleMapCoordinate): Wgs84Coordinate? =
        mapProjector.fromMap(value)

    suspend fun canUndoCoordinateRepair(placeId: Long): Boolean =
        legacyPlaceCoordinates.hasUndo(placeId)

    suspend fun repairCoordinates(
        place: net.extrawdw.apps.locationhistory.data.db.PlaceEntity,
        decision: PlaceCoordinateRepairDecision,
    ): Boolean = legacyPlaceCoordinates.repair(place, decision)

    suspend fun undoCoordinateRepair(
        place: net.extrawdw.apps.locationhistory.data.db.PlaceEntity,
    ): Boolean = legacyPlaceCoordinates.undo(place)

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

    /** One-to-one projection for the split editor; fail the whole preview rather than shift indices. */
    fun projectEditSamples(samples: List<LocationSampleEntity>): List<GoogleMapCoordinate> {
        val projected = samples.mapNotNull {
            mapProjector.coordinate(Wgs84Coordinate(it.latitude, it.longitude))
        }
        return projected.takeIf { it.size == samples.size } ?: emptyList()
    }

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

    /**
     * Where to point the camera before any day data has loaded (or on an empty day): the most
     * recent recorded sample. Avoids opening on the zoom-0 world view. Null on a fresh install.
     */
    suspend fun mostRecentLatLng(): GoogleMapCoordinate? =
        locationRepository.mostRecent()?.let {
            mapProjector.coordinate(Wgs84Coordinate(it.latitude, it.longitude))
        }

    /** Current device location for initial camera / the GPS recenter button. */
    suspend fun currentLatLng(): GoogleMapCoordinate? {
        return currentDeviceLocationForMap()?.coordinate
    }

    /** Current FLP fix projected for both camera use and safe blue-dot selection. */
    suspend fun currentDeviceLocationForMap(): ProjectedDeviceLocation? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            )
                .await()?.let { location ->
                    val wgs = Wgs84Coordinate(location.latitude, location.longitude)
                    mapProjector.coordinate(wgs)?.let { projected ->
                        ProjectedDeviceLocation(
                            coordinate = projected,
                            mainlandCompatibilityActive =
                                mapProjector.requiresMainlandProjection(wgs),
                        )
                    }
                }
        }.getOrNull()
    }

    /**
     * Live current fixes for the custom mainland marker. The UI collects this only while the
     * Timeline map is visible and resumed; cancellation unregisters the callback immediately.
     * Outside the mainland the SDK layer remains the visible dot, but these fixes keep the frame
     * decision current if the device crosses the compatibility boundary during a map session.
     */
    fun deviceLocationsForMap(): Flow<ProjectedDeviceLocation> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            close()
            return@callbackFlow
        }

        fun project(location: android.location.Location): ProjectedDeviceLocation? {
            val wgs = Wgs84Coordinate(location.latitude, location.longitude)
            val coordinate = mapProjector.coordinate(wgs) ?: return null
            return ProjectedDeviceLocation(
                coordinate = coordinate,
                mainlandCompatibilityActive = mapProjector.requiresMainlandProjection(wgs),
            )
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(::project)?.let { trySend(it) }
            }
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            CURRENT_LOCATION_UPDATE_INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(CURRENT_LOCATION_MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(1f)
            .build()

        try {
            try {
                fusedClient.lastLocation.await()?.let(::project)?.let { trySend(it) }
                fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper()).await()
                awaitClose { }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                close(t)
            }
        } finally {
            // Cancellation can land after Play Services registers the callback but before the Task
            // resumes. A finally block covers that window; relying only on awaitClose would leak.
            fusedClient.removeLocationUpdates(callback)
        }
    }

    private fun buildMapState(timeline: TimelineDay): MapState {
        val segments = ArrayList<MapSegment>()
        val visits = ArrayList<MapVisitMarker>()
        val placeRings = LinkedHashMap<Long, MapPlaceRing>() // distinct by place id
        for (item in timeline.items) {
            when (item) {
                is TimelineItem.TripItem -> {
                    val t = item.trip
                    val decoded = Geo.decodePolyline(t.encodedPolyline)
                    mapProjector.paths(decoded).forEach { pts ->
                        if (pts.size >= 2) {
                            segments.add(MapSegment(pts, t.mode, t.confirmed))
                        }
                    }
                }

                is TimelineItem.VisitItem -> {
                    val v = item.visit
                    val visitCenter = Wgs84Coordinate(v.centroidLatitude, v.centroidLongitude)
                    mapProjector.circle(
                        visitCenter,
                        v.radiusMeters,
                    )?.let { circle ->
                        visits.add(
                            MapVisitMarker(
                                circle.center,
                                circle.boundsPoints,
                                v.radiusMeters,
                                v.confirmed,
                            )
                        )
                    }
                    // Yellow ring for the place this visit belongs to (one per place).
                    item.place?.let { p ->
                        mapProjector.placeCircle(p)?.circle?.let { circle ->
                            placeRings[p.id] = MapPlaceRing(
                                circle.center,
                                circle.boundsPoints,
                                circle.radiusMeters,
                            )
                        }
                    }
                }
            }
        }
        val raw = segments.map { it.points }
        return MapState(
            dayEpoch = timeline.dayEpoch,
            profileId = mapProjector.profileId,
            rawPaths = raw,
            segments = segments,
            visits = visits,
            placeRings = placeRings.values.toList(),
        )
    }

    private companion object {
        const val CURRENT_LOCATION_UPDATE_INTERVAL_MS = 5_000L
        const val CURRENT_LOCATION_MIN_INTERVAL_MS = 2_000L
        const val STALE_MS = 30_000L
    }
}
