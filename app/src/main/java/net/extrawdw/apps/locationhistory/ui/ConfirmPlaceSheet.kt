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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.Geo
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import net.extrawdw.apps.locationhistory.data.places.PlaceCandidate
import net.extrawdw.apps.locationhistory.data.repo.PlaceChoice

data class PlaceSearchAnchor(
    val latitude: Double,
    val longitude: Double,
)

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
    val anchor = remember(visit.id) {
        PlaceSearchAnchor(visit.centroidLatitude, visit.centroidLongitude)
    }
    var nearby by remember(anchor) { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    var nearbyRequested by remember(anchor) { mutableStateOf(false) }
    var nearbyLoading by remember(anchor) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    var newName by remember { mutableStateOf(visit.candidateName ?: "") }
    val scope = rememberCoroutineScope()

    fun requestNearby() {
        if (nearbyRequested || nearbyLoading) return
        nearbyRequested = true
        nearbyLoading = true
        scope.launch {
            nearby = runCatching { loadNearby(anchor.latitude, anchor.longitude) }
                .getOrDefault(emptyList())
            nearbyLoading = false
        }
    }
    // Debounced text search against the Maps API.
    LaunchedEffect(query, anchor) {
        if (query.isBlank()) {
            results = emptyList(); return@LaunchedEffect
        }
        delay(350)
        results = searchPlaces(query, anchor.latitude, anchor.longitude)
    }

    val context = LocalContext.current
    val defaultPlaceName = stringResource(R.string.place_default_name)

    val localNearby = remember(localPlaces) {
        localPlaces
            .filter { it.coordinateState == PlaceCoordinateState.WGS84_CANONICAL }
            .map {
                it to Geo.distanceMeters(
                    anchor.latitude,
                    anchor.longitude,
                    it.latitude,
                    it.longitude
                )
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                stringResource(R.string.assign_place_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_places_label)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            NearbyPlacesButton(
                visible = query.isBlank() && (!nearbyRequested || nearbyLoading),
                loading = nearbyLoading,
                onClick = ::requestNearby,
            )

            LazyColumn(
                Modifier
                    // Yield result-list space when the IME reduces the sheet's available height so
                    // the custom-place controls below remain visible.
                    .weight(1f, fill = false)
                    .heightIn(max = 380.dp)
                    .padding(top = 8.dp)
            ) {
                if (results.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.search_results_header)) }
                    items(results, key = { "s${it.googlePlaceId ?: it.name}" }) { c ->
                        PlaceRow(c.name, candidateSubtitle(context, anchor, c)) {
                            onConfirm(
                                PlaceChoice.Google(c)
                            )
                        }
                    }
                }
                if (query.isBlank() && localNearby.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.saved_places_nearest_header)) }
                    items(localNearby, key = { "l${it.id}" }) { place ->
                        val dist = Geo.distanceMeters(
                            anchor.latitude,
                            anchor.longitude,
                            place.latitude,
                            place.longitude
                        )
                        PlaceRow(
                            place.name,
                            stringResource(R.string.distance_away, Format.distance(context, dist))
                        ) { onConfirm(PlaceChoice.Existing(place.id)) }
                    }
                }
                if (query.isBlank() && nearbyRequested) {
                    if (nearbyLoading) {
                        item { SectionLabel(stringResource(R.string.nearby_loading)) }
                    } else if (nearby.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.nearby_empty),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else {
                        item { SectionLabel(stringResource(R.string.nearby_header)) }
                        items(nearby, key = { "g${it.googlePlaceId ?: it.name}" }) { c ->
                            PlaceRow(c.name, candidateSubtitle(context, anchor, c)) {
                                onConfirm(
                                    PlaceChoice.Google(c)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                stringResource(R.string.custom_places_header),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.custom_place_name_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onConfirm(PlaceChoice.NewNamed(newName.ifBlank { defaultPlaceName })) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGooglePlaceSheet(
    anchor: PlaceSearchAnchor,
    loadNearby: suspend (Double, Double) -> List<PlaceCandidate>,
    searchPlaces: suspend (String, Double, Double) -> List<PlaceCandidate>,
    onAdd: (PlaceCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    var nearby by remember(anchor) { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    var nearbyRequested by remember(anchor) { mutableStateOf(false) }
    var nearbyLoading by remember(anchor) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun requestNearby() {
        if (nearbyRequested || nearbyLoading) return
        nearbyRequested = true
        nearbyLoading = true
        scope.launch {
            nearby = runCatching { loadNearby(anchor.latitude, anchor.longitude) }
                .getOrDefault(emptyList())
            nearbyLoading = false
        }
    }
    LaunchedEffect(query, anchor) {
        if (query.isBlank()) {
            results = emptyList(); return@LaunchedEffect
        }
        delay(350)
        results = searchPlaces(query, anchor.latitude, anchor.longitude)
    }

    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                stringResource(R.string.add_place_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_places_label)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            NearbyPlacesButton(
                visible = query.isBlank() && (!nearbyRequested || nearbyLoading),
                loading = nearbyLoading,
                onClick = ::requestNearby,
            )

            LazyColumn(
                Modifier
                    .heightIn(max = 380.dp)
                    .padding(top = 8.dp)
            ) {
                if (results.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.search_results_header)) }
                    items(results, key = { "s${it.googlePlaceId ?: it.name}" }) { c ->
                        PlaceRow(c.name, candidateSubtitle(context, anchor, c)) { onAdd(c) }
                    }
                }
                if (query.isBlank() && nearbyRequested) {
                    if (nearbyLoading) {
                        item { SectionLabel(stringResource(R.string.nearby_loading)) }
                    } else if (nearby.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.nearby_empty),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else {
                        item { SectionLabel(stringResource(R.string.nearby_header)) }
                        items(nearby, key = { "g${it.googlePlaceId ?: it.name}" }) { c ->
                            PlaceRow(c.name, candidateSubtitle(context, anchor, c)) { onAdd(c) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyPlacesButton(
    visible: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    if (!visible) return
    OutlinedButton(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Icon(Icons.Filled.Place, contentDescription = null)
        Text(
            stringResource(
                if (loading) R.string.nearby_loading else R.string.nearby_show
            ),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** "<distance> away · <address/type>" relative to the search hint location. */
private fun candidateSubtitle(
    context: Context,
    anchor: PlaceSearchAnchor,
    c: PlaceCandidate
): String {
    val dist =
        Geo.distanceMeters(anchor.latitude, anchor.longitude, c.latitude, c.longitude)
    val detail = c.address ?: c.primaryType ?: context.getString(R.string.place_default_name)
    return context.getString(R.string.candidate_subtitle, Format.distance(context, dist), detail)
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
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
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
