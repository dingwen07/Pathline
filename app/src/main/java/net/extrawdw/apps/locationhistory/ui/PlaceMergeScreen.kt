package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import kotlin.coroutines.cancellation.CancellationException

/** Search saved places only; ranking is relative to the place being removed, not the device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceMergePickerSheet(
    source: PlaceEntity,
    places: List<PlaceEntity>,
    onMerge: suspend (Long) -> Boolean,
    onMerged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable(source.id) { mutableStateOf("") }
    var selectedId by rememberSaveable(source.id) { mutableStateOf<Long?>(null) }
    val ranked = remember(source, places, query) { rankPlaceMergeTargets(source, places, query) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        // Keep the sheet/list viewport behind the navigation bar. Only list content needs the
        // bottom safe inset; the native sheet continues to handle keyboard overlap exactly once.
        contentWindowInsets = {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                .union(WindowInsets.ime.only(WindowInsetsSides.Bottom))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.place_merge_select), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.place_merge_select_description, source.name),
                modifier = Modifier.padding(vertical = 8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_places_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(
                    top = 12.dp,
                    bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ranked.isEmpty()) item { Text(stringResource(R.string.place_merge_no_targets)) }
                items(ranked, key = { it.id }) { place ->
                    Card(onClick = { selectedId = place.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(place.name, style = MaterialTheme.typography.titleMedium)
                            place.address?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            val distance = placeCenterDistance(source, place)
                            Text(
                                distance?.let { Format.distance(LocalContext.current, it) }
                                    ?: stringResource(R.string.place_merge_distance_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
    places.firstOrNull { it.id == selectedId && it.id != source.id }?.let { destination ->
        PlaceMergeConfirmation(
            source = source,
            destination = destination,
            fromEditor = true,
            onMerge = { onMerge(destination.id) },
            onMerged = onMerged,
            onDismiss = { selectedId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceDuplicatesDialog(
    places: List<PlaceEntity>,
    ignoredPairs: Set<String>?,
    projectPlace: (PlaceEntity) -> ProjectedPlaceCircle?,
    onMerge: suspend (sourceId: Long, destinationId: Long) -> Boolean,
    onIgnore: suspend (PlaceEntity, PlaceEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var firstId by rememberSaveable { mutableStateOf<Long?>(null) }
    var secondId by rememberSaveable { mutableStateOf<Long?>(null) }
    val candidates by produceState<List<PlaceMergeCandidate>?>(null, places, ignoredPairs) {
        value = if (ignoredPairs == null) null else withContext(Dispatchers.Default) {
            findPlaceMergeCandidates(places, ignoredPairs)
        }
    }
    FullScreenDialog(onDismiss = onDismiss) { requestClose ->
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.place_merge_duplicates)) },
                navigationIcon = {
                    IconButton(onClick = { requestClose(onDismiss) }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.action_close))
                    }
                },
            )
        }) { padding ->
            val current = candidates
            if (current == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (places.any { it.coordinateState != PlaceCoordinateState.WGS84_CANONICAL }) {
                        item { Text(stringResource(R.string.place_merge_unresolved_hint)) }
                    }
                    for (sameName in listOf(true, false)) {
                        item {
                            Text(stringResource(if (sameName) R.string.place_merge_same_name
                                else R.string.place_merge_nearby), style = MaterialTheme.typography.titleMedium)
                        }
                        val section = current.filter { it.sameName == sameName }
                        if (section.isEmpty()) item { Text(stringResource(R.string.place_merge_no_duplicates)) }
                        items(section, key = { "${it.first.id}-${it.second.id}" }) { candidate ->
                            Card(onClick = {
                                firstId = candidate.first.id
                                secondId = candidate.second.id
                            }, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(candidate.first.name, style = MaterialTheme.typography.titleMedium)
                                        Text(candidate.second.name, style = MaterialTheme.typography.titleMedium)
                                        Text(Format.distance(LocalContext.current, candidate.distanceMeters),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    PlaceMergeIgnoreButton(onIgnore = {
                                        onIgnore(candidate.first, candidate.second)
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val first = places.firstOrNull { it.id == firstId }
    val second = places.firstOrNull { it.id == secondId }
    if (first != null && second != null) {
        PlaceMergeComparisonDialog(
            first, second, projectPlace, onMerge,
            onIgnore = { onIgnore(first, second); firstId = null; secondId = null },
            onDismiss = { firstId = null; secondId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceMergeComparisonDialog(
    first: PlaceEntity,
    second: PlaceEntity,
    projectPlace: (PlaceEntity) -> ProjectedPlaceCircle?,
    onMerge: suspend (Long, Long) -> Boolean,
    onIgnore: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    var destinationId by rememberSaveable(first.id, second.id) { mutableStateOf<Long?>(null) }
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val sideBySide = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
    ) && !adaptiveInfo.windowPosture.isTabletop
    val firstCard = rememberRetainedContent {
        PlaceMergeComparisonCard(first, projectPlace) { destinationId = first.id }
    }
    val secondCard = rememberRetainedContent {
        PlaceMergeComparisonCard(second, projectPlace) { destinationId = second.id }
    }
    FullScreenDialog(onDismiss = onDismiss, dim = false) { requestClose ->
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.place_merge_compare)) },
                navigationIcon = {
                    IconButton(onClick = { requestClose(onDismiss) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.place_merge_back))
                    }
                },
                actions = { PlaceMergeIgnoreButton(onIgnore) },
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.place_merge_compare_description))
                if (sideBySide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1f)) { firstCard() }
                        Box(Modifier.weight(1f)) { secondCard() }
                    }
                } else {
                    firstCard()
                    secondCard()
                }
            }
        }
    }
    destinationId?.let { id ->
        val destination = if (id == first.id) first else second
        val source = if (id == first.id) second else first
        PlaceMergeConfirmation(source, destination,
            onMerge = { onMerge(source.id, destination.id) }, onMerged = onDismiss,
            onDismiss = { destinationId = null })
    }
}

@Composable
private fun PlaceMergeIgnoreButton(onIgnore: suspend () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        TextButton(enabled = !busy, onClick = {
            if (!busy) {
                busy = true
                failed = false
                scope.launch {
                    try {
                        onIgnore()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }
        }) { Text(stringResource(R.string.place_merge_ignore)) }
        if (failed) Text(stringResource(R.string.place_merge_ignore_failed),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlaceMergeComparisonCard(
    place: PlaceEntity,
    projectPlace: (PlaceEntity) -> ProjectedPlaceCircle?,
    onKeep: () -> Unit,
) {
    val projected = remember(place, projectPlace) { projectPlace(place) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.field_name), style = MaterialTheme.typography.labelMedium)
            Text(place.name, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.field_address), style = MaterialTheme.typography.labelMedium)
            Text(place.address?.takeIf { it.isNotBlank() } ?: stringResource(R.string.place_merge_no_address))
            if (projected != null) {
                val circle = projected.circle
                val camera = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(circle.center.toLatLng(), 17f)
                }
                GoogleMap(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    mapViewFactory = ::ScrollContainerMapView,
                    cameraPositionState = camera,
                    mapColorScheme = rememberMapColorScheme(),
                    uiSettings = MapUiSettings(zoomControlsEnabled = false),
                ) {
                    Circle(center = circle.center.toLatLng(), radius = circle.radiusMeters,
                        strokeColor = MaterialTheme.colorScheme.primary,
                        fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                }
            } else {
                Text(stringResource(R.string.place_edit_location_conversion_failed))
            }
            Button(onClick = onKeep, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.place_merge_keep))
            }
        }
    }
}

@Composable
private fun PlaceMergeConfirmation(
    source: PlaceEntity,
    destination: PlaceEntity,
    fromEditor: Boolean = false,
    onMerge: suspend () -> Boolean,
    onMerged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.place_merge_action)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.place_merge_confirmation, source.name, destination.name))
                if (fromEditor) Text(stringResource(R.string.place_merge_unsaved_hint))
                if (failed) Text(stringResource(R.string.place_merge_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (!busy) {
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            if (onMerge()) onMerged() else failed = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                }
            }) { Text(stringResource(if (busy) R.string.place_merge_merging else R.string.place_merge_action)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
