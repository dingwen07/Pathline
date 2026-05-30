package net.extrawdw.apps.locationhistory.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DbStats(
    val samples: Long = 0, val excluded: Long = 0,
    val visits: Int = 0, val trips: Int = 0, val places: Int = 0,
    val lastFix: String = "—",
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val sampleDao: LocationSampleDao,
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val placeDao: PlaceDao,
) : ViewModel() {
    val stats = MutableStateFlow(DbStats())

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val recent = sampleDao.mostRecent()
        stats.value = DbStats(
            samples = sampleDao.count(),
            excluded = sampleDao.excludedCount(),
            visits = visitDao.count(),
            trips = tripDao.count(),
            places = placeDao.count(),
            lastFix = recent?.let {
                "${TimeBuckets.localDate(it.dayEpoch)} ${Format.time(it.timestampMs)} · ${it.devicePhysicalState.label} · ±${it.accuracy?.toInt() ?: "?"}m"
            } ?: "—",
        )
    }

    fun logFiles(): List<File> = AppLog.sessionFiles()

    suspend fun readLog(file: File): String = withContext(Dispatchers.IO) {
        runCatching { file.readText().takeLast(80_000) }.getOrElse { "(could not read ${file.name})" }
    }
}

/** On-device diagnostics: live DB stats + a session-log browser. Export/share is kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var openFile by remember { mutableStateOf<File?>(null) }
    var content by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(openFile) { openFile?.let { content = viewModel.readLog(it) } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false),
    ) {
        // Inside the dialog window: back returns a log view to the list; otherwise predictive-back
        // dismisses the whole dialog.
        BackHandler(enabled = openFile != null) { openFile = null }
        val backProgress by rememberPredictiveBackProgress(enabled = openFile == null, onDismiss = onDismiss)
        Surface(Modifier.fillMaxSize().predictiveBack(backProgress)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(openFile?.name ?: "Diagnostics") },
                        navigationIcon = {
                            IconButton(onClick = { if (openFile != null) openFile = null else onDismiss() }) {
                                Icon(
                                    if (openFile != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                                    contentDescription = "Back",
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { shareLogs(context) }) {
                                Icon(Icons.Filled.Share, contentDescription = "Share logs")
                            }
                        },
                    )
                },
            ) { padding ->
                if (openFile != null) {
                    // Monospace, no line wrap (scroll both ways).
                    SelectionContainer(Modifier.fillMaxSize().padding(padding)) {
                        Text(
                            content.ifEmpty { "(empty)" },
                            modifier = Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            softWrap = false,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Card(Modifier.fillMaxWidth().padding(16.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Internal data", style = MaterialTheme.typography.titleMedium)
                                StatRow("Location samples", "${stats.samples} (${stats.excluded} excluded)")
                                StatRow("Visits", stats.visits.toString())
                                StatRow("Trips", stats.trips.toString())
                                StatRow("Places", stats.places.toString())
                                StatRow("Last fix", stats.lastFix)
                            }
                        }
                        Text(
                            "Session logs (newest first) — tap to view",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        val files = remember(stats) { viewModel.logFiles() }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(files, key = { it.name }) { f ->
                                Column(
                                    Modifier.fillMaxWidth().clickable { openFile = f }.padding(horizontal = 16.dp, vertical = 12.dp),
                                ) {
                                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${f.length() / 1024} KB · ${fileTime(f)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                HorizontalDivider()
                            }
                            if (files.isEmpty()) {
                                item { Text("No logs yet.", Modifier.padding(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun fileTime(f: File): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(f.lastModified()))

/** Share the most recent session log files via a FileProvider content URI. */
fun shareLogs(context: Context) {
    val files = AppLog.sessionFiles().take(5)
    if (files.isEmpty()) return
    val authority = "${context.packageName}.fileprovider"
    val uris = ArrayList<android.net.Uri>(files.size)
    files.forEach { f ->
        runCatching { androidx.core.content.FileProvider.getUriForFile(context, authority, f) }
            .getOrNull()?.let { uris.add(it) }
    }
    if (uris.isEmpty()) return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share Pathline logs"))
}
