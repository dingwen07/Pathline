package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(viewModel: PlacesViewModel = hiltViewModel()) {
    val places by viewModel.places.collectAsStateWithLifecycle()
    val pending by viewModel.unconfirmedVisits.collectAsStateWithLifecycle()
    val visitCounts by viewModel.visitCounts.collectAsStateWithLifecycle()

    var editPlace by remember { mutableStateOf<PlaceEntity?>(null) }
    var deletePlace by remember { mutableStateOf<PlaceEntity?>(null) }
    var assignVisit by remember { mutableStateOf<VisitEntity?>(null) }
    var detailPlaceId by remember { mutableStateOf<Long?>(null) }
    var addPlaceAnchor by remember { mutableStateOf<PlaceSearchAnchor?>(null) }
    var showNoLocation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.places_title)) },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val anchor = viewModel.placeSearchAnchor()
                                if (anchor != null) {
                                    addPlaceAnchor =
                                        PlaceSearchAnchor(anchor.latitude, anchor.longitude)
                                } else {
                                    showNoLocation = true
                                }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.cd_place_add)
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pending.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.places_pending_header)) }
                items(pending, key = { "pending-${it.id}" }) { visit ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                visit.candidateName ?: "%.4f, %.4f".format(
                                    visit.centroidLatitude,
                                    visit.centroidLongitude
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.time_range,
                                    Format.time(visit.startMs),
                                    Format.time(visit.endMs)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            AssistChip(
                                onClick = { assignVisit = visit },
                                label = { Text(stringResource(R.string.places_assign)) },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.places_saved_header)) }
            items(places, key = { "place-${it.id}" }) { place ->
                val count = visitCounts[place.id] ?: 0
                Card(onClick = { detailPlaceId = place.id }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Place, contentDescription = null)
                        Column(
                            Modifier
                                .padding(start = 12.dp)
                                .weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(place.name, style = MaterialTheme.typography.titleMedium)
                                if (place.fixed) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = stringResource(R.string.cd_place_locked),
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            place.address?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                stringResource(
                                    R.string.place_summary,
                                    pluralStringResource(R.plurals.visits_count, count, count),
                                    place.radiusMeters.toInt(),
                                    stringResource(place.source.labelRes),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (count == 0) {
                            IconButton(onClick = { deletePlace = place }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.cd_place_delete)
                                )
                            }
                        }
                        IconButton(onClick = { editPlace = place }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.cd_place_edit)
                            )
                        }
                    }
                }
            }
            if (places.isEmpty() && pending.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.places_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
        }
    }

    editPlace?.let { place ->
        PlaceEditDialog(
            place = place,
            loadAnnotations = { target, id -> viewModel.loadAnnotations(target, id) },
            onSave = { name, address, lat, lon, radius, fixed ->
                viewModel.updatePlace(place, name, address, lat, lon, radius, fixed)
                editPlace = null
            },
            onSaveAnnotations = { note, tags ->
                viewModel.saveAnnotations(AnnotationTarget.PLACE, place.id, note, tags)
            },
            onDismiss = { editPlace = null },
        )
    }

    deletePlace?.let { place ->
        AlertDialog(
            onDismissRequest = { deletePlace = null },
            title = { Text(stringResource(R.string.places_delete_title)) },
            text = { Text(stringResource(R.string.places_delete_message, place.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteIfUnvisited(place.id)
                        deletePlace = null
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    deletePlace = null
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    assignVisit?.let { visit ->
        ConfirmPlaceSheet(
            visit = visit,
            localPlaces = places,
            loadNearby = { lat, lon -> viewModel.nearbySuggestions(lat, lon) },
            searchPlaces = { q, lat, lon -> viewModel.searchPlaces(q, lat, lon) },
            onConfirm = { choice -> viewModel.confirmVisit(visit.id, choice); assignVisit = null },
            onDismiss = { assignVisit = null },
        )
    }

    addPlaceAnchor?.let { anchor ->
        AddGooglePlaceSheet(
            anchor = anchor,
            loadNearby = { lat, lon -> viewModel.nearbySuggestions(lat, lon) },
            searchPlaces = { q, lat, lon -> viewModel.searchPlaces(q, lat, lon) },
            onAdd = { candidate ->
                viewModel.addGooglePlace(candidate)
                addPlaceAnchor = null
            },
            onDismiss = { addPlaceAnchor = null },
        )
    }

    if (showNoLocation) {
        AlertDialog(
            onDismissRequest = { showNoLocation = false },
            title = { Text(stringResource(R.string.add_place_no_location_title)) },
            text = { Text(stringResource(R.string.add_place_no_location_message)) },
            confirmButton = {
                TextButton(onClick = { showNoLocation = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    detailPlaceId?.let { id ->
        PlaceDetailDialog(placeId = id, onDismiss = { detailPlaceId = null })
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}
