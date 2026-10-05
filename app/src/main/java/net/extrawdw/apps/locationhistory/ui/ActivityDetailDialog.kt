package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.data.db.TripEntity
import net.extrawdw.apps.locationhistory.domain.AnnotationData
import net.extrawdw.apps.locationhistory.domain.ActivityNeighbors
import net.extrawdw.apps.locationhistory.domain.TimelineItem
import net.extrawdw.apps.locationhistory.domain.TripPlayback
import net.extrawdw.apps.locationhistory.domain.averageSpeedMetersPerSecond
import net.extrawdw.apps.locationhistory.ui.icons.arrow_split
import net.extrawdw.apps.locationhistory.ui.icons.ios_share

/** Trip details are available before confirmation; annotations belong to stable, confirmed rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityDetailDialog(
    trip: TripEntity,
    paths: List<List<GoogleMapCoordinate>>,
    neighbors: ActivityNeighbors = ActivityNeighbors(),
    playback: TripPlayback? = null,
    projectPath: (List<Wgs84Coordinate>) -> List<List<GoogleMapCoordinate>> = { emptyList() },
    loadAnnotations: suspend (AnnotationTarget, Long) -> AnnotationData,
    onConfirm: suspend (TransportMode) -> Boolean,
    onSave: (String, List<String>) -> Unit,
    onExport: () -> Unit,
    exporting: Boolean = false,
    onSplit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val durationMs = (trip.endMs - trip.startMs).coerceAtLeast(0)
    val durationSeconds = durationMs / 1000f
    val elapsedSeconds = rememberSaveable(trip.id) { mutableFloatStateOf(durationSeconds) }
    val progressTime = remember(trip.startMs, durationMs, elapsedSeconds) {
        { trip.startMs + elapsedMillis(elapsedSeconds.floatValue, durationMs) }
    }
    val inspectedTime = rememberSaveable(trip.id) { mutableStateOf<Long?>(null) }
    val altitudeTime = remember(inspectedTime, progressTime) {
        { inspectedTime.value ?: progressTime() }
    }
    val onAltitudeTimeChange = remember(inspectedTime) {
        { time: Long -> inspectedTime.value = time }
    }
    val inspectedPosition by remember(playback, projectPath, inspectedTime) {
        derivedStateOf {
            inspectedTime.value?.let { playback?.positionAt(it) }
                ?.let { projectPath(listOf(it)).firstOrNull()?.firstOrNull() }
        }
    }
    // Cursor/slider updates stay immediate. Conflate map requests so long routes cannot queue
    // expensive projections on the UI thread; always render the latest position after a drag.
    val drawnPaths by produceState(paths, playback, paths, projectPath, progressTime) {
        snapshotFlow(progressTime).conflate().collect { time ->
            value = if (time == trip.endMs || playback?.hasPositions != true) paths
            else withContext(Dispatchers.Default) { projectPath(playback.pathUntil(time)) }
            delay(50)
        }
    }
    var confirming by remember(trip.id) { mutableStateOf(false) }
    var confirmationFailed by remember(trip.id) { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    val annotations = if (trip.confirmed) {
        rememberAnnotationEditState(AnnotationTarget.TRIP, trip.id, loadAnnotations)
    } else null

    FullScreenDialog(onDismiss = onDismiss) { requestClose ->
        AdaptivePlaceLayout(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.activity_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = { requestClose(onDismiss) }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.action_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = onExport, enabled = !exporting) {
                            Icon(ios_share, stringResource(R.string.activity_export_gpx))
                        }
                        Box {
                            IconButton(enabled = !confirming, onClick = { modeMenu = true }) {
                                Icon(
                                    if (trip.confirmed) Icons.Filled.Edit else Icons.Filled.Check,
                                    stringResource(if (trip.confirmed) R.string.chip_change_mode else R.string.chip_confirm),
                                )
                            }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                TransportMode.SELECTABLE.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(mode.labelRes)) },
                                        leadingIcon = { Icon(Format.transportIcon(mode), null, tint = modeColor(mode)) },
                                        onClick = {
                                            modeMenu = false
                                            confirming = true
                                            scope.launch {
                                                try {
                                                    confirmationFailed = !onConfirm(mode)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (_: Exception) {
                                                    confirmationFailed = true
                                                } finally {
                                                    confirming = false
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(enabled = !confirming, onClick = { requestClose(onSplit) }) {
                            Icon(arrow_split, stringResource(R.string.menu_split))
                        }
                    },
                )
            },
            map = { edgeToEdge ->
                ActivityRouteMap(
                    paths = paths,
                    drawnPaths = drawnPaths,
                    inspectedPosition = inspectedPosition,
                    mode = trip.mode,
                    contentPadding = if (edgeToEdge) WindowInsets.safeDrawing.asPaddingValues() else PaddingValues(),
                )
            },
        ) { padding, inlineMap ->
            Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .clipToBounds()
                    .verticalScroll(rememberScrollState()),
            ) {
                inlineMap?.let { map ->
                    Box(Modifier.fillMaxWidth().height(280.dp)) { map() }
                }
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActivityProgressControl(trip.startMs, durationMs, elapsedSeconds, playback?.hasPositions == true)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Format.transportIcon(trip.mode), null, tint = modeColor(trip.mode))
                        Text(stringResource(trip.mode.labelRes), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(if (trip.confirmed) R.string.activity_confirmed else R.string.chip_unconfirmed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Format.compactDate(trip.dayEpoch), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.time_range, Format.time(trip.startMs), Format.time(trip.endMs)),
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                        )
                    }
                    val distance = Format.distance(context, trip.distanceMeters)
                    val duration = Format.duration(context, trip.startMs, trip.endMs)
                    val speed = trip.averageSpeedMetersPerSecond()
                    Text(
                        if (speed == null) stringResource(R.string.trip_brief, distance, duration)
                        else stringResource(R.string.activity_metrics, distance, duration, Format.speed(context, speed)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    playback?.let {
                        ActivityAltitudeChart(it, trip.startMs, trip.endMs, altitudeTime, onAltitudeTimeChange, modeColor(trip.mode))
                    }
                    if (confirmationFailed) {
                        Text(stringResource(R.string.activity_confirm_failed), color = MaterialTheme.colorScheme.error)
                    }
                    neighbors.before?.let { ActivityNeighbor(stringResource(R.string.activity_before), it) }
                    neighbors.after?.let { ActivityNeighbor(stringResource(R.string.activity_after), it) }
                    HorizontalDivider()
                    if (annotations != null) {
                        AnnotationEditorBody(annotations, enabled = annotations.loaded)
                        TextButton(
                            enabled = annotations.loaded,
                            modifier = Modifier.align(Alignment.End),
                            onClick = { requestClose { onSave(annotations.note, annotations.tags.toList()) } },
                        ) { Text(stringResource(R.string.action_save)) }
                    } else {
                        Text(stringResource(R.string.activity_confirm_notes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun elapsedMillis(seconds: Float, durationMs: Long): Long =
    if (seconds >= durationMs / 1000f) durationMs
    else (seconds.coerceAtLeast(0f) * 1000).toLong().coerceAtMost(durationMs)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityProgressControl(startMs: Long, durationMs: Long, elapsedSeconds: MutableFloatState, hasPositions: Boolean) {
    val elapsedMs = elapsedMillis(elapsedSeconds.floatValue, durationMs)
    val durationSeconds = durationMs / 1000f
    val progressLabel = stringResource(R.string.activity_progress)
    val elapsedLabel = stringResource(R.string.activity_elapsed, Format.elapsed(elapsedMs), Format.elapsed(durationMs))
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Format.preciseTime(startMs + elapsedMs), style = MaterialTheme.typography.titleSmall)
            Text(elapsedLabel, style = MaterialTheme.typography.bodyMedium)
        }
        val slider = remember(startMs, durationMs) {
            SliderState(value = elapsedSeconds.floatValue, trackRange = 0f..durationSeconds.coerceAtLeast(1f))
        }
        slider.value = elapsedSeconds.floatValue.coerceIn(0f, durationSeconds)
        Slider(
            state = slider,
            onValueChange = { elapsedSeconds.floatValue = it },
            enabled = durationMs > 0 && hasPositions,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = progressLabel
                stateDescription = elapsedLabel
            },
        )
    }
}

@Composable
private fun ActivityNeighbor(label: String, item: TimelineItem) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(if (item.confirmed) R.string.activity_confirmed else R.string.chip_unconfirmed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val mode = (item as? TimelineItem.TripItem)?.trip?.mode
                Icon(
                    imageVector = mode?.let { Format.transportIcon(it) } ?: Icons.Filled.Place,
                    contentDescription = null,
                    tint = mode?.let { modeColor(it) } ?: MaterialTheme.colorScheme.primary,
                )
                Text(
                    when (item) {
                        is TimelineItem.VisitItem -> item.displayName
                        is TimelineItem.TripItem -> stringResource(item.trip.mode.labelRes)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(Format.date(TimeBuckets.dayEpoch(item.startMs)), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.time_range_duration, Format.time(item.startMs), Format.time(item.endMs), Format.duration(context, item.startMs, item.endMs)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ActivityRouteMap(
    paths: List<List<GoogleMapCoordinate>>,
    drawnPaths: List<List<GoogleMapCoordinate>>,
    inspectedPosition: GoogleMapCoordinate?,
    mode: TransportMode,
    contentPadding: PaddingValues,
) {
    val points = remember(paths) { paths.flatten().map { it.toLatLng() } }
    if (points.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.activity_route_unavailable))
        }
        return
    }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(points.first(), 15f)
    }
    var loaded by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val markerPadding = with(LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(loaded, size, paths) {
        if (!loaded || size == IntSize.Zero) return@LaunchedEffect
        val bounds = LatLngBounds.builder().apply { points.forEach { include(it) } }.build()
        if (bounds.northeast == bounds.southwest) {
            camera.move(CameraUpdateFactory.newLatLngZoom(points.first(), 16f))
        } else {
            camera.move(CameraUpdateFactory.newLatLngBounds(bounds, markerPadding.coerceAtMost(minOf(size.width, size.height) / 3)))
        }
    }
    if (rememberMapComposed(onScreen = true)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize().onSizeChanged { size = it },
            cameraPositionState = camera,
            mapColorScheme = rememberMapColorScheme(),
            mapViewFactory = { context, options -> ScrollContainerMapView(context, options) },
            uiSettings = MapUiSettings(zoomControlsEnabled = false),
            contentPadding = contentPadding,
            onMapLoaded = { loaded = true },
        ) {
            drawnPaths.filter { it.size >= 2 }.forEach { path ->
                Polyline(points = path.map { it.toLatLng() }, color = modeColor(mode), width = 16f)
            }
            Marker(state = rememberUpdatedMarkerState(points.first()), title = stringResource(R.string.activity_start))
            Marker(state = rememberUpdatedMarkerState(points.last()), title = stringResource(R.string.activity_end))
            inspectedPosition?.let { position ->
                val color = MaterialTheme.colorScheme.primary
                MarkerComposable(
                    "altitude-position", color,
                    state = rememberUpdatedMarkerState(position.toLatLng()),
                    anchor = Offset(0.5f, 0.5f),
                    flat = true,
                    zIndex = 20f,
                    title = stringResource(R.string.activity_altitude),
                ) { VisitCenterDot(color) }
            }
        }
    }
}
