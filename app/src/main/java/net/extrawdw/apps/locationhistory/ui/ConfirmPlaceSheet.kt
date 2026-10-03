package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
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

private enum class AssignPlaceInput { SEARCH, CUSTOM }

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
    mapsApiKeyConfigured: Boolean,
    onConfirm: (PlaceChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val anchor = remember(visit.id) {
        PlaceSearchAnchor(visit.centroidLatitude, visit.centroidLongitude)
    }
    var nearby by remember(anchor) { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    var nearbyRequested by remember(anchor) { mutableStateOf(false) }
    var nearbyLoading by remember(anchor) { mutableStateOf(false) }
    var query by rememberSaveable(visit.id) { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var activeInput by remember { mutableStateOf<AssignPlaceInput?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    val customFocusRequester = remember { FocusRequester() }
    var newName by rememberSaveable(visit.id) { mutableStateOf(visit.candidateName ?: "") }
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    fun requestNearby() {
        if (!mapsApiKeyConfigured || nearbyRequested || nearbyLoading) return
        nearbyRequested = true
        nearbyLoading = true
        scope.launch {
            nearby = runCatching { loadNearby(anchor.latitude, anchor.longitude) }
                .getOrDefault(emptyList())
            nearbyLoading = false
        }
    }
    fun requestSearch() {
        val requested = query.trim()
        if (!mapsApiKeyConfigured || requested.isEmpty() || searchLoading) return
        searchLoading = true
        scope.launch {
            results = runCatching {
                searchPlaces(requested, anchor.latitude, anchor.longitude)
            }.getOrDefault(emptyList())
            searchLoading = false
        }
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

    // Base the form arrangement on window size, not animated IME height, so keyboard animation
    // and changing focus do not move a text field into a different composition.
    val windowSize = LocalWindowInfo.current.containerDpSize
    val wideWindow = windowSize.width >= 600.dp
    val horizontalInputs = wideWindow && windowSize.height < 700.dp
    val compactVerticalInputs = !wideWindow && windowSize.height < 700.dp
    val nearbyInList = horizontalInputs || compactVerticalInputs
    val bottomPadding = 8.dp
    val keepCustomBehindKeyboard = !wideWindow && searchFocused
    // Reparenting a focus target clears native focus. Restore it only when the window changes
    // the form arrangement, never on an IME frame or an ordinary switch between these inputs.
    LaunchedEffect(horizontalInputs) {
        when (activeInput) {
            AssignPlaceInput.SEARCH -> searchFocusRequester.requestFocus()
            AssignPlaceInput.CUSTOM -> customFocusRequester.requestFocus()
            null -> Unit
        }
    }

    @Composable
    fun SearchInput(modifier: Modifier = Modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isBlank()) results = emptyList()
            },
            label = { Text(stringResource(R.string.search_places_label)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(
                    onClick = ::requestSearch,
                    enabled = mapsApiKeyConfigured && query.isNotBlank() && !searchLoading,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { requestSearch() }),
            singleLine = true,
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(searchFocusRequester)
                .onFocusChanged {
                    searchFocused = it.isFocused
                    if (it.isFocused) activeInput = AssignPlaceInput.SEARCH
                    if (it.isFocused && sheetState.targetValue != SheetValue.Expanded) {
                        scope.launch { sheetState.expand() }
                    }
                }
                .padding(top = 8.dp),
        )
    }

    @Composable
    fun CustomInput(modifier: Modifier = Modifier) {
        Row(
            modifier
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
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(customFocusRequester)
                    .onFocusChanged {
                        if (it.isFocused) activeInput = AssignPlaceInput.CUSTOM
                        if (it.isFocused && sheetState.targetValue != SheetValue.Expanded) {
                            scope.launch { sheetState.expand() }
                        }
                    },
            )
            Button(onClick = { onConfirm(PlaceChoice.NewNamed(newName.ifBlank { defaultPlaceName })) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.action_save))
            }
        }
    }

    val retainedSearchInput = rememberRetainedContent { SearchInput() }
    val retainedCustomInput = rememberRetainedContent { CustomInput() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.statusBarsPadding(),
        contentWindowInsets = {
            if (horizontalInputs) {
                // The inputs are pinned above the list. Let the list viewport reach behind the
                // navigation bar, while the native sheet still keeps the whole form above the IME.
                BottomSheetDefaults.modalWindowInsets.only(WindowInsetsSides.Top)
                    .union(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                    .union(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
            } else if (keepCustomBehindKeyboard) {
                WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical)
            } else {
                BottomSheetDefaults.modalWindowInsets
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = if (wideWindow) 720.dp else Dp.Infinity)
                .padding(start = 20.dp, end = 20.dp, bottom = if (horizontalInputs) 0.dp else bottomPadding)
        ) {
            Text(
                stringResource(R.string.assign_place_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            if (horizontalInputs) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { retainedSearchInput() }
                    Box(Modifier.weight(1f)) { retainedCustomInput() }
                }
            } else {
                retainedSearchInput()
            }

            if (!mapsApiKeyConfigured) {
                Text(
                    stringResource(R.string.maps_api_key_required_for_search),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (!nearbyInList) {
                NearbyPlacesButton(
                    visible = query.isBlank() && (!nearbyRequested || nearbyLoading),
                    loading = nearbyLoading,
                    enabled = mapsApiKeyConfigured,
                    onClick = ::requestNearby,
                )
            }

            LazyColumn(
                Modifier
                    // A phone keeps Custom at the bottom even when the search results are empty.
                    // While Search is focused, only the list avoids the IME; Custom stays behind it.
                    .weight(1f, fill = !wideWindow)
                    .heightIn(max = 380.dp)
                    .then(
                        if (keepCustomBehindKeyboard) {
                            Modifier.fillMaxSize().recalculateWindowInsets().imePadding()
                        } else {
                            Modifier
                        }
                    )
                    .padding(top = 8.dp)
            ) {
                if (nearbyInList) {
                    item {
                        NearbyPlacesButton(
                            visible = query.isBlank() && (!nearbyRequested || nearbyLoading),
                            loading = nearbyLoading,
                            enabled = mapsApiKeyConfigured,
                            onClick = ::requestNearby,
                        )
                    }
                }
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
                if (horizontalInputs) {
                    item {
                        // This scrolls with the places, so rows can draw behind the system bar
                        // and the final row can still be scrolled fully clear of it. IME insets
                        // consumed by the sheet automatically reduce this spacer to zero.
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }

            if (!horizontalInputs) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                // The input label identifies Custom in short phone windows. Keep room for both
                // pinned inputs and the keyboard; Nearby remains in the scrolling result list.
                if (!compactVerticalInputs) {
                    Text(
                        stringResource(R.string.custom_places_header),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                retainedCustomInput()
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
    mapsApiKeyConfigured: Boolean,
    onAdd: (PlaceCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    var nearby by remember(anchor) { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    var nearbyRequested by remember(anchor) { mutableStateOf(false) }
    var nearbyLoading by remember(anchor) { mutableStateOf(false) }
    var query by rememberSaveable(anchor) { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    fun requestNearby() {
        if (!mapsApiKeyConfigured || nearbyRequested || nearbyLoading) return
        nearbyRequested = true
        nearbyLoading = true
        scope.launch {
            nearby = runCatching { loadNearby(anchor.latitude, anchor.longitude) }
                .getOrDefault(emptyList())
            nearbyLoading = false
        }
    }
    fun requestSearch() {
        val requested = query.trim()
        if (!mapsApiKeyConfigured || requested.isEmpty() || searchLoading) return
        searchLoading = true
        scope.launch {
            results = runCatching {
                searchPlaces(requested, anchor.latitude, anchor.longitude)
            }.getOrDefault(emptyList())
            searchLoading = false
        }
    }

    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.statusBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                stringResource(R.string.add_place_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.isBlank()) results = emptyList()
                },
                label = { Text(stringResource(R.string.search_places_label)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(
                        onClick = ::requestSearch,
                        enabled = mapsApiKeyConfigured && query.isNotBlank() && !searchLoading,
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { requestSearch() }),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        if (it.isFocused && sheetState.targetValue != SheetValue.Expanded) {
                            scope.launch { sheetState.expand() }
                        }
                    }
                    .padding(top = 8.dp),
            )

            if (!mapsApiKeyConfigured) {
                Text(
                    stringResource(R.string.maps_api_key_required_for_search),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            NearbyPlacesButton(
                visible = query.isBlank() && (!nearbyRequested || nearbyLoading),
                loading = nearbyLoading,
                enabled = mapsApiKeyConfigured,
                onClick = ::requestNearby,
            )

            LazyColumn(
                Modifier
                    .weight(1f, fill = false)
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
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (!visible) return
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
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
