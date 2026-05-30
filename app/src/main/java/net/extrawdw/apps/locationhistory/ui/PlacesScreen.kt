package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    Scaffold(topBar = { TopAppBar(title = { Text("Places") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pending.isNotEmpty()) {
                item { SectionHeader("Pending confirmation") }
                items(pending, key = { "pending-${it.id}" }) { visit ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                visit.candidateName ?: "%.4f, %.4f".format(visit.centroidLatitude, visit.centroidLongitude),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${Format.time(visit.startMs)} – ${Format.time(visit.endMs)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            AssistChip(
                                onClick = { assignVisit = visit },
                                label = { Text("Assign place") },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            item { SectionHeader("Saved places") }
            items(places, key = { "place-${it.id}" }) { place ->
                val count = visitCounts[place.id] ?: 0
                Card(onClick = { detailPlaceId = place.id }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Place, contentDescription = null)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(place.name, style = MaterialTheme.typography.titleMedium)
                                if (place.fixed) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = "Locked",
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            place.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(
                                "$count ${if (count == 1) "visit" else "visits"} · r=${place.radiusMeters.toInt()} m · ${place.source.name.lowercase()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (count == 0) {
                            IconButton(onClick = { deletePlace = place }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete place")
                            }
                        }
                        IconButton(onClick = { editPlace = place }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit place")
                        }
                    }
                }
            }
            if (places.isEmpty() && pending.isEmpty()) {
                item {
                    Text(
                        "Places you visit will appear here once detected.",
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
            onSave = { name, address, lat, lon, radius, fixed ->
                viewModel.updatePlace(place, name, address, lat, lon, radius, fixed)
                editPlace = null
            },
            onDismiss = { editPlace = null },
        )
    }

    deletePlace?.let { place ->
        AlertDialog(
            onDismissRequest = { deletePlace = null },
            title = { Text("Delete place?") },
            text = { Text("${place.name} has no visits and can be removed from saved places.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteIfUnvisited(place.id)
                        deletePlace = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletePlace = null }) { Text("Cancel") }
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

    detailPlaceId?.let { id ->
        PlaceDetailDialog(placeId = id, onDismiss = { detailPlaceId = null })
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}
