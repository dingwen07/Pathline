package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity

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
    onSave: (name: String, address: String?, lat: Double, lon: Double, radius: Double, fixed: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(place.name) }
    var address by remember { mutableStateOf(place.address ?: "") }
    var center by remember { mutableStateOf(LatLng(place.latitude, place.longitude)) }
    var radius by remember { mutableFloatStateOf(place.radiusMeters.toFloat()) }
    var fixed by remember { mutableStateOf(place.fixed) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(center, 16f)
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
                        title = { Text(stringResource(R.string.place_edit_title)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
                        },
                        actions = {
                            TextButton(onClick = {
                                onSave(name, address.ifBlank { null }, center.latitude, center.longitude, radius.toDouble(), fixed)
                            }) { Text(stringResource(R.string.action_save)) }
                        },
                    )
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.field_name)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = address, onValueChange = { address = it },
                        label = { Text(stringResource(R.string.field_address)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        stringResource(R.string.place_edit_radius_hint, radius.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Slider(value = radius, onValueChange = { radius = it }, valueRange = 20f..300f)
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.place_lock_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.place_lock_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = fixed, onCheckedChange = { fixed = it })
                    }
                    // Map fills the rest with no scrolling parent, so gestures aren't intercepted.
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraPositionState,
                            mapColorScheme = ComposeMapColorScheme.FOLLOW_SYSTEM,
                            uiSettings = MapUiSettings(zoomControlsEnabled = false),
                            onMapClick = { center = it },
                        ) {
                            Circle(
                                center = center,
                                radius = radius.toDouble(),
                                strokeColor = androidx.compose.ui.graphics.Color(0xFF1E88E5),
                                strokeWidth = 4f,
                                fillColor = androidx.compose.ui.graphics.Color(0x331E88E5),
                            )
                        }
                    }
                }
            }
        }
    }
}
