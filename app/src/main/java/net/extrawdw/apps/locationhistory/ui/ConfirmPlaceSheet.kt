package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.places.PlaceCandidate
import net.extrawdw.apps.locationhistory.data.repo.PlaceChoice

/**
 * Resolve a visit's place: **search** Google Places by name, pick a nearby suggestion, pick an
 * existing saved place (ranked by distance), or save a custom place. Used both when confirming a
 * new visit and when changing an existing one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmPlaceSheet(
    visit: VisitEntity,
    localPlaces: List<PlaceEntity>,
    loadNearby: suspend (Double, Double) -> List<PlaceCandidate>,
    searchPlaces: suspend (String, Double, Double) -> List<PlaceCandidate>,
    onConfirm: (PlaceChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var nearby by remember { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    var newName by remember { mutableStateOf(visit.candidateName ?: "") }

    LaunchedEffect(visit.id) {
        nearby = loadNearby(visit.centroidLatitude, visit.centroidLongitude)
    }
    // Debounced text search against the Maps API.
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        results = searchPlaces(query, visit.centroidLatitude, visit.centroidLongitude)
    }

    val localNearby = remember(localPlaces) {
        localPlaces
            .map { it to Geo.distanceMeters(visit.centroidLatitude, visit.centroidLongitude, it.latitude, it.longitude) }
            .sortedBy { it.second }
            .map { it.first }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Assign place", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search places") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                if (results.isNotEmpty()) {
                    item { SectionLabel("Search results (nearest first)") }
                    items(results, key = { "s${it.googlePlaceId ?: it.name}" }) { c ->
                        PlaceRow(c.name, candidateSubtitle(visit, c)) { onConfirm(PlaceChoice.Google(c)) }
                    }
                }
                if (query.isBlank() && localNearby.isNotEmpty()) {
                    item { SectionLabel("Saved places (nearest first)") }
                    items(localNearby, key = { "l${it.id}" }) { place ->
                        val dist = Geo.distanceMeters(visit.centroidLatitude, visit.centroidLongitude, place.latitude, place.longitude)
                        PlaceRow(place.name, "${Format.distance(dist)} away") { onConfirm(PlaceChoice.Existing(place.id)) }
                    }
                }
                if (query.isBlank() && nearby.isNotEmpty()) {
                    item { SectionLabel("Nearby (nearest first)") }
                    items(nearby, key = { "g${it.googlePlaceId ?: it.name}" }) { c ->
                        PlaceRow(c.name, candidateSubtitle(visit, c)) { onConfirm(PlaceChoice.Google(c)) }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Custom places", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Custom place name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onConfirm(PlaceChoice.NewNamed(newName.ifBlank { "Place" })) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Save")
                }
            }
        }
    }
}

/** "<distance> away · <address/type>" relative to the visit location. */
private fun candidateSubtitle(visit: VisitEntity, c: PlaceCandidate): String {
    val dist = Geo.distanceMeters(visit.centroidLatitude, visit.centroidLongitude, c.latitude, c.longitude)
    val detail = c.address ?: c.primaryType ?: "Place"
    return "${Format.distance(dist)} away · $detail"
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun PlaceRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Place, contentDescription = null)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}
