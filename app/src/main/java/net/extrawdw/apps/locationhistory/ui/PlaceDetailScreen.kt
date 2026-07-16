package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import dagger.hilt.android.lifecycle.HiltViewModel
import net.extrawdw.apps.locationhistory.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import javax.inject.Inject

data class PlaceVisitMarker(
    val visitId: Long,
    val center: GoogleMapCoordinate,
    val boundsPoints: List<GoogleMapCoordinate>,
    val radiusMeters: Double,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaceDetailViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val mapProjector: GoogleMapProjector,
) : ViewModel() {
    val mapProfileId: String get() = mapProjector.profileId
    private val placeId = MutableStateFlow<Long?>(null)
    fun load(id: Long) {
        placeId.value = id
    }

    val place: StateFlow<PlaceEntity?> = placeId
        .flatMapLatest { if (it == null) flowOf(null) else placeRepository.observeById(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val visits: StateFlow<List<VisitEntity>> = placeId
        .flatMapLatest {
            if (it == null) flowOf(emptyList()) else placeRepository.observeVisitsForPlace(
                it
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun projectPlace(place: PlaceEntity): ProjectedPlaceCircle? =
        mapProjector.placePreviewCircle(place)

    val visitMarkers: StateFlow<List<PlaceVisitMarker>> = placeId
        .flatMapLatest {
            if (it == null) {
                flowOf(emptyList())
            } else {
                placeRepository.observeVisitsForPlace(it).map { visits ->
                    visits.mapNotNull { visit ->
                        mapProjector.circle(
                            Wgs84Coordinate(
                                visit.centroidLatitude,
                                visit.centroidLongitude,
                            ),
                            visit.radiusMeters,
                        )?.let { circle ->
                            PlaceVisitMarker(
                                visitId = visit.id,
                                center = circle.center,
                                boundsPoints = circle.boundsPoints,
                                radiusMeters = visit.radiusMeters,
                            )
                        }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Full-screen place detail: the place's center + radius circle on a map, and a list of past visits.
 * Scrolling the visit list pans the map to that visit's location, and each visit's center is also
 * dropped as a small dot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailDialog(
    placeId: Long,
    onDismiss: () -> Unit,
    viewModel: PlaceDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(placeId) { viewModel.load(placeId) }
    val observedPlace by viewModel.place.collectAsStateWithLifecycle()
    val observedVisits by viewModel.visits.collectAsStateWithLifecycle()
    val observedVisitMarkers by viewModel.visitMarkers.collectAsStateWithLifecycle()
    // The WhileSubscribed flows switch independently. Never pair a newly requested place with
    // the previous dialog's projection/visits during their brief hand-off window.
    val place = observedPlace?.takeIf { it.id == placeId }
    val projectedPlace = remember(place, viewModel.mapProfileId) {
        place?.let(viewModel::projectPlace)
    }
    val visits = if (place == null) emptyList() else observedVisits.filter { it.placeId == placeId }
    val activeVisitIds = visits.mapTo(HashSet()) { it.id }
    val visitMarkers = if (place == null) emptyList() else {
        observedVisitMarkers.filter { it.visitId in activeVisitIds }
    }

    val listState = rememberLazyListState()
    val visibleVisitIds by remember(visits) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { visits.getOrNull(it.index)?.id }
                .toSet()
        }
    }
    val visibleMarkers = remember(visitMarkers, visibleVisitIds) {
        if (visibleVisitIds.isEmpty()) visitMarkers.take(4) else visitMarkers.filter { it.visitId in visibleVisitIds }
    }
    // Only follow the list once the user actually scrolls, so the map stays put where it opened.
    val hasScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    FullScreenDialog(onDismiss = onDismiss) { requestClose ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            place?.name ?: stringResource(R.string.place_default_name)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { requestClose(onDismiss) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close)
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                ) {
                    // Compose the map only once the place is loaded, so its camera can be seeded
                    // at the place in the constructor (no world-view flash, like the editor).
                    place?.let { p ->
                        projectedPlace?.let { projected ->
                            key(p.id, projected.circle.center, viewModel.mapProfileId) {
                                PlaceDetailMap(
                                    placeCircle = projected.circle,
                                    visibleMarkers = visibleMarkers,
                                    followList = hasScrolled,
                                    profileId = viewModel.mapProfileId,
                                )
                            }
                        }
                    }
                }
                place?.let { p ->
                    if (p.coordinateState != PlaceCoordinateState.WGS84_CANONICAL) {
                        Text(
                            stringResource(
                                if (projectedPlace != null) {
                                    R.string.place_coordinate_legacy_preview
                                } else {
                                    R.string.place_coordinate_review_message
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    val visitsLabel =
                        pluralStringResource(R.plurals.visits_count, visits.size, visits.size)
                    Text(
                        if (p.fixed) {
                            stringResource(
                                R.string.place_detail_summary_locked,
                                visitsLabel,
                                p.radiusMeters.toInt()
                            )
                        } else {
                            stringResource(
                                R.string.place_detail_summary,
                                visitsLabel,
                                p.radiusMeters.toInt()
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    p.address?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
                HorizontalDivider()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(visits, key = { it.id }) { v ->
                        val context = LocalContext.current
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                Format.date(v.dayEpoch),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(
                                    R.string.time_range_duration,
                                    Format.time(v.startMs),
                                    Format.time(v.endMs),
                                    Format.duration(context, v.startMs, v.endMs),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(
                                    R.string.visit_detail_stats,
                                    v.radiusMeters.toInt(),
                                    pluralStringResource(
                                        R.plurals.fixes_count,
                                        v.sampleCount,
                                        v.sampleCount
                                    ),
                                    (v.reliability * 100).toInt(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * The detail map. The camera is seeded at the place in [rememberCameraPositionState]'s constructor —
 * the same pattern the place editor uses — so the first composed frame is already at the place rather
 * than the default world view. While [followList] is true (the user has scrolled the visit list) the
 * camera pans to keep the visible visits framed.
 */
@Composable
private fun PlaceDetailMap(
    placeCircle: ProjectedMapCircle,
    visibleMarkers: List<PlaceVisitMarker>,
    followList: Boolean,
    profileId: String,
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(placeCircle.center.toLatLng(), 16f)
    }
    LaunchedEffect(followList, visibleMarkers.map { it.visitId }, profileId) {
        if (!followList || visibleMarkers.isEmpty()) return@LaunchedEffect
        val bounds = visibleVisitBounds(visibleMarkers)
        if (bounds != null) {
            runCatching {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngBounds(
                        bounds,
                        80
                    )
                )
            }
                .onFailure {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngZoom(
                            visibleMarkers.first().center.toLatLng(),
                            16f
                        )
                    )
                }
        }
    }
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        mapColorScheme = rememberMapColorScheme(),
        uiSettings = MapUiSettings(zoomControlsEnabled = false),
    ) {
        // The saved place's own radius, drawn as a yellow ring (matches the timeline map).
        Circle(
            center = placeCircle.center.toLatLng(),
            radius = placeCircle.radiusMeters,
            strokeColor = Color.Transparent,
            strokeWidth = 0f,
            fillColor = Color(0xFFFFD54F).copy(alpha = 0.18f),
        )
        // Visible visit rows only: translucent radius circle + fixed center dot.
        val blue = Color(0xFF4285F4)
        visibleMarkers.forEach { marker ->
            Circle(
                center = marker.center.toLatLng(),
                radius = marker.radiusMeters,
                strokeColor = Color.Transparent,
                strokeWidth = 0f,
                fillColor = blue.copy(alpha = 0.22f),
            )
            MarkerComposable(
                marker.visitId,
                state = rememberUpdatedMarkerState(position = marker.center.toLatLng()),
                anchor = Offset(0.5f, 0.5f),
                flat = true,
                zIndex = 10f,
            ) {
                VisitCenterDot(blue)
            }
        }
    }
}

private fun visibleVisitBounds(markers: List<PlaceVisitMarker>): LatLngBounds? {
    if (markers.isEmpty()) return null
    val builder = LatLngBounds.builder()
    markers.forEach { marker ->
        marker.boundsPoints.forEach { builder.include(it.toLatLng()) }
    }
    return runCatching { builder.build() }.getOrNull()
}
