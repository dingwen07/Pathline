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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import javax.inject.Inject

data class PlaceVisitMarker(
    val visitId: Long,
    val center: LatLng,
    val radiusMeters: Double,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaceDetailViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
) : ViewModel() {
    private val placeId = MutableStateFlow<Long?>(null)
    fun load(id: Long) { placeId.value = id }

    val place: StateFlow<PlaceEntity?> = placeId
        .flatMapLatest { if (it == null) flowOf(null) else placeRepository.observeById(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val visits: StateFlow<List<VisitEntity>> = placeId
        .flatMapLatest { if (it == null) flowOf(emptyList()) else placeRepository.observeVisitsForPlace(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val visitMarkers: StateFlow<List<PlaceVisitMarker>> = placeId
        .flatMapLatest {
            if (it == null) {
                flowOf(emptyList())
            } else {
                placeRepository.observeVisitsForPlace(it).map { visits ->
                    visits.map { visit ->
                        PlaceVisitMarker(
                            visitId = visit.id,
                            center = LatLng(visit.centroidLatitude, visit.centroidLongitude),
                            radiusMeters = visit.radiusMeters,
                        )
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
    val place by viewModel.place.collectAsStateWithLifecycle()
    val visits by viewModel.visits.collectAsStateWithLifecycle()
    val visitMarkers by viewModel.visitMarkers.collectAsStateWithLifecycle()

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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false),
    ) {
        val backProgress by rememberPredictiveBackProgress(onDismiss = onDismiss)
        Surface(Modifier.fillMaxSize().predictiveBack(backProgress)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(place?.name ?: "Place") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                        },
                    )
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Box(Modifier.fillMaxWidth().height(280.dp)) {
                        // Compose the map only once the place is loaded, so its camera can be seeded
                        // at the place in the constructor (no world-view flash, like the editor).
                        place?.let { p ->
                            PlaceDetailMap(
                                place = p,
                                visibleMarkers = visibleMarkers,
                                followList = hasScrolled,
                            )
                        }
                    }
                    place?.let { p ->
                        Text(
                            "${visits.size} ${if (visits.size == 1) "visit" else "visits"} · r=${p.radiusMeters.toInt()} m" +
                                (if (p.fixed) " · locked" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        p.address?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                    HorizontalDivider()
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(visits, key = { it.id }) { v ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(Format.date(v.dayEpoch), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${Format.time(v.startMs)} – ${Format.time(v.endMs)} · ${Format.duration(v.startMs, v.endMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            HorizontalDivider()
                        }
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
    place: PlaceEntity,
    visibleMarkers: List<PlaceVisitMarker>,
    followList: Boolean,
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(place.latitude, place.longitude), 16f)
    }
    LaunchedEffect(followList, visibleMarkers.map { it.visitId }) {
        if (!followList || visibleMarkers.isEmpty()) return@LaunchedEffect
        val bounds = visibleVisitBounds(visibleMarkers)
        if (bounds != null) {
            runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
                .onFailure { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(visibleMarkers.first().center, 16f)) }
        }
    }
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        mapColorScheme = ComposeMapColorScheme.FOLLOW_SYSTEM,
        uiSettings = MapUiSettings(zoomControlsEnabled = false),
    ) {
        // The saved place's own radius, drawn as a yellow ring (matches the timeline map).
        Circle(
            center = LatLng(place.latitude, place.longitude),
            radius = place.radiusMeters,
            strokeColor = Color.Transparent,
            strokeWidth = 0f,
            fillColor = Color(0xFFFFD54F).copy(alpha = 0.18f),
        )
        // Visible visit rows only: translucent radius circle + fixed center dot.
        val blue = Color(0xFF4285F4)
        visibleMarkers.forEach { marker ->
            Circle(
                center = marker.center,
                radius = marker.radiusMeters,
                strokeColor = Color.Transparent,
                strokeWidth = 0f,
                fillColor = blue.copy(alpha = 0.22f),
            )
            MarkerComposable(
                marker.visitId,
                state = rememberUpdatedMarkerState(position = marker.center),
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
        val box = Geo.boundingBox(
            marker.center.latitude,
            marker.center.longitude,
            marker.radiusMeters.coerceAtLeast(45.0),
        )
        builder.include(LatLng(box[0], box[1]))
        builder.include(LatLng(box[2], box[3]))
    }
    return runCatching { builder.build() }.getOrNull()
}
