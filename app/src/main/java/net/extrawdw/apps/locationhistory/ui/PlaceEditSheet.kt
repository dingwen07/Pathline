package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateRepairDecision
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.domain.AnnotationData
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Full-screen place editor. It is a [Dialog] (not a bottom sheet) so the map can use pan and
 * two-finger zoom/rotate gestures without fighting a sheet drag, and there is no scrolling parent
 * over the map to steal vertical drags. Edits name, address, center (tap the map), radius, and the
 * **Fixed** flag that stops auto-updates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceEditDialog(
    place: PlaceEntity,
    loadAnnotations: suspend (AnnotationTarget, Long) -> AnnotationData,
    projectPlace: (PlaceEntity) -> ProjectedPlaceCircle?,
    projectCoordinate: (Wgs84Coordinate) -> GoogleMapCoordinate?,
    normalizeMapCoordinate: (GoogleMapCoordinate) -> Wgs84Coordinate?,
    canUndoRepair: suspend () -> Boolean,
    onSave: (
        name: String,
        address: String?,
        lat: Double,
        lon: Double,
        radius: Double,
        fixed: Boolean,
        centerChanged: Boolean,
        radiusChanged: Boolean,
    ) -> Unit,
    onSaveAnnotations: (note: String, tags: List<String>) -> Unit,
    onRepair: suspend (PlaceCoordinateRepairDecision) -> Boolean,
    onUndoRepair: suspend () -> Boolean,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(place.name) }
    var address by remember { mutableStateOf(place.address ?: "") }
    val initialProjection = remember(
        place.id,
        place.latitude,
        place.longitude,
        place.coordinateState,
    ) { projectPlace(place) }
    var canonicalCenter by remember(place.id) {
        mutableStateOf(
            initialProjection?.canonicalCenter
                ?: Wgs84Coordinate(place.latitude, place.longitude)
        )
    }
    var mapCenter by remember(place.id) { mutableStateOf(initialProjection?.circle?.center) }
    var centerChanged by remember(place.id) { mutableStateOf(false) }
    val mapInteractionEnabled = remember(
        place.coordinateState,
        initialProjection?.circle?.center,
    ) {
        place.coordinateState == PlaceCoordinateState.WGS84_CANONICAL &&
                initialProjection?.circle?.center?.let(normalizeMapCoordinate) != null
    }
    var radius by remember(place.id, place.radiusMeters) {
        mutableFloatStateOf(place.radiusMeters.toFloat())
    }
    // A stored adaptive radius is a Double and usually cannot round-trip through Slider's Float.
    // Track an actual user gesture so opening and saving the editor is a geometry-exact no-op.
    var radiusChanged by remember(place.id) { mutableStateOf(false) }
    var fixed by remember { mutableStateOf(place.fixed) }
    var undoAvailable by remember(place.id) { mutableStateOf(false) }
    var pendingRepair by remember(place.id) {
        mutableStateOf<PlaceCoordinateRepairDecision?>(null)
    }
    var confirmUndo by remember(place.id) { mutableStateOf(false) }
    var repairFailed by remember(place.id) { mutableStateOf(false) }
    var repairInFlight by remember(place.id) { mutableStateOf(false) }
    val annotations = rememberAnnotationEditState(AnnotationTarget.PLACE, place.id, loadAnnotations)
    val scope = rememberCoroutineScope()

    fun launchCoordinateOperation(operation: suspend () -> Boolean) {
        if (repairInFlight) return
        repairInFlight = true
        repairFailed = false
        scope.launch {
            try {
                if (!operation()) repairFailed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                repairFailed = true
            } finally {
                repairInFlight = false
            }
        }
    }

    LaunchedEffect(place.id) { undoAvailable = canUndoRepair() }

    val cameraPositionState = rememberCameraPositionState {
        mapCenter?.let { position = CameraPosition.fromLatLngZoom(it.toLatLng(), 16f) }
    }

    FullScreenDialog(
        onDismiss = onDismiss,
        dismissEnabled = !repairInFlight,
    ) { requestClose ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.place_edit_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = { requestClose(onDismiss) },
                            enabled = !repairInFlight,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close)
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                requestClose {
                                    onSave(
                                        name,
                                        address.ifBlank { null },
                                        canonicalCenter.latitude,
                                        canonicalCenter.longitude,
                                        radius.toDouble(),
                                        fixed || radius > Constants.PLACE_MAX_RADIUS_METERS,
                                        centerChanged,
                                        radiusChanged,
                                    )
                                    onSaveAnnotations(
                                        annotations.note,
                                        annotations.tags.toList(),
                                    )
                                }
                            },
                            enabled = !repairInFlight,
                        ) { Text(stringResource(R.string.action_save)) }
                    },
                )
            },
        ) { padding ->
            // Scrollable so the folded-in note/tags fields have room; the map sits at a fixed height.
            // The map's own view disallows parent touch interception during pan/zoom, so its gestures
            // still work inside the scroll container.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) }, singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    enabled = !repairInFlight,
                )
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text(stringResource(R.string.field_address)) }, singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    enabled = !repairInFlight,
                )
                if (place.coordinateState != PlaceCoordinateState.WGS84_CANONICAL) {
                    Text(
                        stringResource(R.string.place_coordinate_review_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (place.anchorLatitude != null && place.anchorLongitude != null) {
                        TextButton(
                            onClick = {
                                pendingRepair = PlaceCoordinateRepairDecision
                                    .GOOGLE_BASELINE_AS_HISTORICAL_PROVIDER
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !repairInFlight,
                        ) {
                            Text(stringResource(R.string.place_repair_google_historical))
                        }
                        TextButton(
                            onClick = {
                                pendingRepair = PlaceCoordinateRepairDecision
                                    .GOOGLE_BASELINE_AS_WGS84
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !repairInFlight,
                        ) {
                            Text(stringResource(R.string.place_repair_google_wgs84))
                        }
                    }
                    if (place.coordinateState !=
                        PlaceCoordinateState.LEGACY_MIXED_CENTER_GOOGLE_MAP_BASELINE
                    ) {
                        TextButton(
                            onClick = {
                                pendingRepair = PlaceCoordinateRepairDecision.SAVED_CENTER_AS_WGS84
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !repairInFlight,
                        ) {
                            Text(stringResource(R.string.place_repair_saved_wgs84))
                        }
                        TextButton(
                            onClick = {
                                pendingRepair = PlaceCoordinateRepairDecision
                                    .SAVED_CENTER_AS_HISTORICAL_MAP
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !repairInFlight,
                        ) {
                            Text(stringResource(R.string.place_repair_saved_historical))
                        }
                    }
                } else if (undoAvailable) {
                    TextButton(
                        onClick = { confirmUndo = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !repairInFlight,
                    ) {
                        Text(stringResource(R.string.place_repair_undo))
                    }
                }
                if (repairFailed) {
                    Text(
                        stringResource(R.string.place_repair_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.place_edit_radius_hint, radius.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = radius,
                    onValueChange = {
                        radius = it
                        radiusChanged = true
                    },
                    valueRange = 20f..Constants.PLACE_MANUAL_MAX_RADIUS_METERS.toFloat(),
                    enabled = !repairInFlight,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.place_lock_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.place_lock_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = fixed,
                        onCheckedChange = { fixed = it },
                        enabled = !repairInFlight,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                ) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        mapColorScheme = rememberMapColorScheme(),
                        uiSettings = MapUiSettings(zoomControlsEnabled = false),
                        onMapClick = { clicked ->
                            if (mapInteractionEnabled && !repairInFlight) {
                                val normalized = normalizeMapCoordinate(
                                    GoogleMapCoordinate(clicked.latitude, clicked.longitude)
                                )
                                val projected = normalized?.let(projectCoordinate)
                                if (normalized != null && projected != null) {
                                    canonicalCenter = normalized
                                    mapCenter = projected
                                    centerChanged = true
                                }
                            }
                        },
                    ) {
                        mapCenter?.let { center ->
                            Circle(
                                center = center.toLatLng(),
                                radius = radius.toDouble(),
                                strokeColor = androidx.compose.ui.graphics.Color(0xFF1E88E5),
                                strokeWidth = 4f,
                                fillColor = androidx.compose.ui.graphics.Color(0x331E88E5),
                            )
                        }
                    }
                }
                if (!mapInteractionEnabled) {
                    Text(
                        stringResource(R.string.place_edit_location_write_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (
                    place.coordinateState != PlaceCoordinateState.WGS84_CANONICAL &&
                    mapCenter != null
                ) {
                    Text(
                        stringResource(R.string.place_coordinate_preview_google_baseline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                AnnotationEditorBody(
                    annotations,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 16.dp),
                    enabled = !repairInFlight,
                )
            }
        }

        pendingRepair?.let { decision ->
            val message = when (decision) {
                PlaceCoordinateRepairDecision.GOOGLE_BASELINE_AS_HISTORICAL_PROVIDER ->
                    R.string.place_repair_confirm_google_historical

                PlaceCoordinateRepairDecision.GOOGLE_BASELINE_AS_WGS84 ->
                    R.string.place_repair_confirm_google_wgs84

                PlaceCoordinateRepairDecision.SAVED_CENTER_AS_HISTORICAL_MAP ->
                    R.string.place_repair_confirm_saved_historical

                else -> R.string.place_repair_confirm_saved_wgs84
            }
            AlertDialog(
                onDismissRequest = { if (!repairInFlight) pendingRepair = null },
                title = { Text(stringResource(R.string.place_repair_confirm_title)) },
                text = {
                    Column {
                        Text(stringResource(message))
                        Text(
                            stringResource(R.string.place_repair_unsaved_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingRepair = null
                            launchCoordinateOperation { onRepair(decision) }
                        },
                        enabled = !repairInFlight,
                    ) { Text(stringResource(R.string.action_confirm)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingRepair = null },
                        enabled = !repairInFlight,
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
        if (confirmUndo) {
            AlertDialog(
                onDismissRequest = { if (!repairInFlight) confirmUndo = false },
                title = { Text(stringResource(R.string.place_repair_undo)) },
                text = {
                    Column {
                        Text(stringResource(R.string.place_repair_undo_confirm))
                        Text(
                            stringResource(R.string.place_repair_unsaved_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmUndo = false
                            launchCoordinateOperation(onUndoRepair)
                        },
                        enabled = !repairInFlight,
                    ) { Text(stringResource(R.string.place_repair_undo)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmUndo = false },
                        enabled = !repairInFlight,
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}
