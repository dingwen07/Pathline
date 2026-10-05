package net.extrawdw.apps.locationhistory.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.repo.DeletePlaceOptions
import net.extrawdw.apps.locationhistory.data.repo.DataDeletionSummary
import net.extrawdw.apps.locationhistory.data.repo.EdgeActivityBehavior
import net.extrawdw.apps.locationhistory.data.repo.ResetDataOptions
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManageDataDialog(onDismiss: () -> Unit, viewModel: DataManagementViewModel = hiltViewModel()) {
    var page by rememberSaveable { mutableStateOf("home") }
    var reset by rememberSaveable { mutableStateOf(false) }
    var selectedPlace by rememberSaveable(stateSaver = PlaceDialogSaver) { mutableStateOf<PlaceEntity?>(null) }
    var completed by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val rangeStateHolder = rememberSaveableStateHolder()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy = status == DataDeletionStatus.RUNNING
    FullScreenDialog(onDismiss, dismissEnabled = !busy) { close ->
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.data_manage)) }, navigationIcon = {
                IconButton(enabled = !busy, onClick = { close(onDismiss) }) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_close))
                }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.data_manage_description))
                    if (completed) Text(stringResource(R.string.data_deleted), color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { page = "range" }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.data_delete_range))
                    }
                    OutlinedButton(onClick = { page = "place" }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.data_delete_place))
                    }
                    OutlinedButton(onClick = { reset = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.data_reset_all), color = MaterialTheme.colorScheme.error)
                    }
            }
        }
        if (page == "range") ModalBottomSheet(
            onDismissRequest = { if (!busy) page = "home" },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            contentWindowInsets = { dataManagementSheetInsets() },
        ) {
            Text(stringResource(R.string.data_delete_range), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp))
            rangeStateHolder.SaveableStateProvider("range") {
                DatePurgeForm(Modifier.fillMaxWidth(), viewModel) {
                    page = "home"; completed = true
                }
            }
        }
        if (page == "place") ModalBottomSheet(
            onDismissRequest = { if (!busy) page = "home" },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            contentWindowInsets = { dataManagementSheetInsets() },
        ) {
            val filtered = remember(places, query) { filterDeletionPlaces(places, query) }
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
                Text(stringResource(R.string.data_delete_place), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                OutlinedTextField(query, { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.search_places_label)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(stringResource(R.string.data_place_order), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 16.dp,
                    bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (filtered.isEmpty()) item { Text(stringResource(R.string.place_merge_no_targets)) }
                    items(filtered, key = { it.id }) { place ->
                        Card(onClick = { selectedPlace = place }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(place.name, style = MaterialTheme.typography.titleMedium)
                                place.address?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            }
                        }
                    }
                }
            }
        }
        if (reset) ResetDataDialog(viewModel,
            onDeleted = { reset = false; completed = true }, onDismiss = { reset = false },
        )
        // Hold the selected row while the transaction removes it so completion can dismiss cleanly.
        selectedPlace?.let { selected ->
            DeletePlaceDialog(selected, onDismiss = { selectedPlace = null },
                onDeleted = { selectedPlace = null; page = "home"; completed = true }, viewModel = viewModel)
        }
    }
}

/** Let the sheet/list background reach the system bar; inset scrollable content only. */
@Composable
private fun dataManagementSheetInsets() =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        .union(WindowInsets.ime.only(WindowInsetsSides.Bottom))

@Composable
internal fun ResetDataDialog(viewModel: DataManagementViewModel, onDeleted: () -> Unit, onDismiss: () -> Unit) {
    var appSettings by rememberSaveable { mutableStateOf(false) }
    var mapsSettings by rememberSaveable { mutableStateOf(false) }
    var logs by rememberSaveable { mutableStateOf(false) }
    var onboarding by rememberSaveable { mutableStateOf(true) }
    val options = ResetDataOptions(appSettings, mapsSettings, logs, onboarding)
    DataDeletionConfirmation(
        title = stringResource(R.string.data_reset_all), description = stringResource(R.string.data_reset_description),
        viewModel = viewModel, onConfirm = { viewModel.resetAllData(options) },
        previewKey = options, loadPreview = { viewModel.previewReset(options) },
        onDeleted = onDeleted, onDismiss = onDismiss,
    ) { enabled ->
        DataCheckRow(stringResource(R.string.data_reset_app_settings), appSettings, enabled) { appSettings = it }
        DataCheckRow(stringResource(R.string.data_reset_maps_settings), mapsSettings, enabled) { mapsSettings = it }
        DataCheckRow(stringResource(R.string.data_reset_logs), logs, enabled) { logs = it }
        DataCheckRow(stringResource(R.string.data_reset_onboarding), onboarding, enabled) { onboarding = it }
    }
}

@Composable
internal fun DeletePlaceDialog(
    place: PlaceEntity,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: DataManagementViewModel = hiltViewModel(),
) {
    var connectedTrips by rememberSaveable(place.id) { mutableStateOf(true) }
    var wholeDays by rememberSaveable(place.id) { mutableStateOf(false) }
    DataDeletionConfirmation(
        title = stringResource(R.string.data_delete_place),
        description = stringResource(R.string.data_delete_place_description, place.name),
        viewModel = viewModel,
        onConfirm = { viewModel.deletePlace(place.id, DeletePlaceOptions(connectedTrips, wholeDays)) },
        previewKey = listOf(place.id, connectedTrips, wholeDays),
        loadPreview = { viewModel.previewPlace(place.id, DeletePlaceOptions(connectedTrips, wholeDays)) },
        onDeleted = onDeleted, onDismiss = onDismiss,
    ) { enabled ->
        DataCheckRow(stringResource(R.string.data_delete_connected_trips), connectedTrips, enabled) { connectedTrips = it }
        DataCheckRow(stringResource(R.string.data_delete_visit_days), wholeDays, enabled) { wholeDays = it }
        if (wholeDays) Text(stringResource(R.string.data_delete_visit_days_description),
            style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePurgeForm(modifier: Modifier, viewModel: DataManagementViewModel, onDeleted: () -> Unit) {
    val today = LocalDate.now().toEpochDay()
    var first by rememberSaveable { mutableLongStateOf(today) }
    var last by rememberSaveable { mutableLongStateOf(today) }
    var cap by rememberSaveable { mutableStateOf(true) }
    var emptyPlaces by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val startText = LocalDate.ofEpochDay(first).format(dateFormatter)
    val endText = LocalDate.ofEpochDay(last).format(dateFormatter)
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)
        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.data_date_zone, ZoneId.systemDefault().id))
        OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (first == last) startText else stringResource(R.string.date_range, startText, endText))
        }
        Text(stringResource(R.string.data_edge_title), style = MaterialTheme.typography.titleMedium)
        DataRadioRow(stringResource(R.string.data_edge_cap), cap) { cap = true }
        DataRadioRow(stringResource(R.string.data_edge_whole), !cap) { cap = false }
        DataCheckRow(stringResource(R.string.data_delete_empty_places), emptyPlaces) { emptyPlaces = it }
        Button(onClick = { confirm = true }, enabled = first <= last, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.data_review_deletion))
        }
    }
    if (picker) DateRangePickerDialog(
        initialStart = first, initialEnd = last,
        onConfirm = { start, end -> first = start; last = end; picker = false },
        onDismiss = { picker = false }, confirmLabel = stringResource(R.string.action_apply),
    )
    if (confirm) DataDeletionConfirmation(
        title = stringResource(R.string.data_delete_range),
        description = stringResource(R.string.data_range_confirmation, startText, endText) + "\n\n" +
            stringResource(if (cap) R.string.data_edge_cap else R.string.data_edge_whole) +
            if (emptyPlaces) "\n\n" + stringResource(R.string.data_delete_empty_places) else "",
        viewModel = viewModel,
        previewKey = listOf(first, last, cap, emptyPlaces),
        loadPreview = { viewModel.previewRange(first, last,
            if (cap) EdgeActivityBehavior.CAP else EdgeActivityBehavior.DELETE_WHOLE, emptyPlaces) },
        onConfirm = { viewModel.deleteDateRange(first, last,
            if (cap) EdgeActivityBehavior.CAP else EdgeActivityBehavior.DELETE_WHOLE, emptyPlaces) },
        onDeleted = { confirm = false; onDeleted() }, onDismiss = { confirm = false },
    )
}

@Composable
internal fun DataDeletionConfirmation(
    title: String, description: String, viewModel: DataManagementViewModel,
    onConfirm: () -> Unit, onDeleted: () -> Unit, onDismiss: () -> Unit,
    previewKey: Any, loadPreview: suspend () -> DataDeletionSummary,
    options: @Composable (enabled: Boolean) -> Unit = {},
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy = status == DataDeletionStatus.RUNNING
    var retry by remember { mutableIntStateOf(0) }
    var closing by remember { mutableStateOf(false) }
    var swiping by remember { mutableStateOf(false) }
    var committed by remember { mutableStateOf(false) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val openness by animateFloatAsState(if (closing) 0f else 1f, tween(220), label = "deleteDialogClose",
        finishedListener = { if (it == 0f) { viewModel.dismissResult(); latestDismiss() } })
    val gesture by animateFloatAsState(if (swiping || committed) backProgress else 0f, label = "deleteDialogBack")
    val requestDismiss = { if (!busy) closing = true }
    val currentLoadPreview by rememberUpdatedState(loadPreview)
    var preview by remember { mutableStateOf<Result<DataDeletionSummary>?>(null) }
    var displayedSummary by remember { mutableStateOf<DataDeletionSummary?>(null) }
    var loadedKey by remember { mutableStateOf<Any?>(null) }
    var loadedRetry by remember { mutableIntStateOf(-1) }
    val previewCurrent = loadedKey == previewKey && loadedRetry == retry
    LaunchedEffect(previewKey, retry) {
        val result = try { Result.success(currentLoadPreview()) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        preview = result
        result.getOrNull()?.let { displayedSummary = it }
        loadedKey = previewKey
        loadedRetry = retry
    }
    val latestOnDeleted by rememberUpdatedState(onDeleted)
    LaunchedEffect(status) {
        if (status == DataDeletionStatus.DONE) {
            viewModel.dismissResult()
            latestOnDeleted()
        }
    }
    AlertDialog(
        onDismissRequest = requestDismiss,
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        modifier = Modifier.graphicsLayer {
            val scale = (0.92f + 0.08f * openness) * (1f - 0.10f * gesture)
            scaleX = scale; scaleY = scale
            alpha = openness * (1f - 0.15f * gesture)
        },
        title = {
            // Register inside the dialog so its dispatcher owns the gesture, not the screen below it.
            PredictiveBackHandler(enabled = !busy && !closing) { events ->
                try {
                    events.collect { swiping = true; backProgress = it.progress }
                    swiping = false
                    committed = true
                    requestDismiss()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    swiping = false
                    backProgress = 0f
                }
            }
            Text(title)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description)
                options(!busy && !closing)
                val summary = displayedSummary
                if (summary != null) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.tertiary) {
                        Text(stringResource(R.string.data_summary_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.data_summary_counts, summary.samples, summary.visits, summary.trips, summary.places))
                        Text(stringResource(R.string.data_summary_annotations, summary.notes, summary.memories,
                            summary.tags, summary.tagLinks, summary.groups, summary.groupMemberships))
                        summary.logFiles?.let { Text(stringResource(R.string.data_summary_logs, it, summary.dataApiLogs ?: 0)) }
                        if (summary.cappedActivities > 0) Text(stringResource(R.string.data_summary_capped, summary.cappedActivities))
                    }
                }
                if (previewCurrent && preview?.isFailure == true) {
                    Text(stringResource(R.string.data_preview_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.data_preview_retry)) }
                }
                // Keep the prior counts and reserve progress space while changed options recalculate.
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (!previewCurrent || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.data_delete_warning))
                if (status == DataDeletionStatus.FAILED) Text(stringResource(R.string.data_delete_failed),
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !closing && previewCurrent && preview?.isSuccess == true, onClick = onConfirm) {
                Text(stringResource(R.string.data_delete_action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(enabled = !busy && !closing, onClick = requestDismiss) {
            Text(stringResource(R.string.action_cancel))
        } },
    )
}

@Composable
private fun DataCheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(text, Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
private fun DataRadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(text, Modifier.weight(1f).padding(start = 12.dp))
    }
}
