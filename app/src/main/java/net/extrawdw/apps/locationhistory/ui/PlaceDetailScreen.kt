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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.PlaceRepository
import net.extrawdw.apps.locationhistory.domain.VisitGeometry
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
    private val locationRepository: LocationRepository,
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
                placeRepository.observeVisitsForPlace(it).mapLatest { visits ->
                    visits.map { visit ->
                        val samples = locationRepository.range(visit.startMs, visit.endMs + 1)
                            .filter { sample -> sample.includedInComputation }
                        val geom = VisitGeometry.compute(
                            samples,
                            visit.centroidLatitude,
                            visit.centroidLongitude,
                        )
                        PlaceVisitMarker(
                            visitId = visit.id,
                            center = LatLng(geom.latitude, geom.longitude),
                            radiusMeters = geom.radiusMeters,
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

    val cameraPositionState = rememberCameraPositionState()
    val listState = rememberLazyListState()

    // Center the map on the place once it loads.
    LaunchedEffect(place?.id) {
        place?.let {
            cameraPositionState.move(CameraUpdateFactory.newCameraPosition(CameraPosition.fromLatLngZoom(LatLng(it.latitude, it.longitude), 16f)))
        }
    }
    // Pan to the visit at the top of the list as the user scrolls.
    LaunchedEffect(listState, visits, visitMarkers) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                visits.getOrNull(idx)?.let { v ->
                    val target = visitMarkers.firstOrNull { it.visitId == v.id }?.center
                        ?: LatLng(v.centroidLatitude, v.centroidLongitude)
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(target, 17f))
                }
            }
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
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraPositionState,
                            mapColorScheme = ComposeMapColorScheme.FOLLOW_SYSTEM,
                            uiSettings = MapUiSettings(zoomControlsEnabled = false),
                        ) {
                            // Place: yellow translucent ring (no dot, no edge).
                            place?.let { p ->
                                Circle(
                                    center = LatLng(p.latitude, p.longitude), radius = p.radiusMeters,
                                    strokeColor = Color.Transparent, strokeWidth = 0f,
                                    fillColor = Color(0xFFFFD54F).copy(alpha = 0.18f),
                                )
                            }
                            // Each past visit "my location" style: translucent circle + small dot.
                            visitMarkers.forEach { marker ->
                                val blue = Color(0xFF4285F4)
                                Circle(center = marker.center, radius = marker.radiusMeters, strokeColor = Color.Transparent, strokeWidth = 0f, fillColor = blue.copy(alpha = 0.22f))
                                Circle(center = marker.center, radius = 7.0, strokeColor = Color.Transparent, strokeWidth = 0f, fillColor = blue)
                            }
                        }
                    }
                    place?.let { p ->
                        Text(
                            "${visits.size} ${if (visits.size == 1) "visit" else "visits"} · r=${p.radiusMeters.toInt()} m" +
                                (if (p.fixed) " · fixed" else ""),
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
