package net.extrawdw.apps.locationhistory.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.location.LocationManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.locationhistory.BuildConfig
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.LocationSampleDao
import net.extrawdw.apps.locationhistory.data.db.PlaceDao
import net.extrawdw.apps.locationhistory.data.db.TripDao
import net.extrawdw.apps.locationhistory.data.db.VisitDao
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.service.RecorderServiceController
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private const val MAX_LOG_VIEW_CHARS = 80_000

data class DbStats(
    val samples: Long = 0, val excluded: Long = 0,
    val visits: Int = 0, val trips: Int = 0, val places: Int = 0,
    val lastFix: String = "—",
)

data class WorkerDebugRow(
    @param:StringRes val labelRes: Int,
    val state: String,
    val attempts: Int,
    val id: String,
)

data class DebugRow(
    @param:StringRes val labelRes: Int,
    val value: String,
)

data class EnvironmentStats(
    val appVersion: String = "—",
    val androidVersion: String = "—",
    val device: String = "—",
    val locationServicesEnabled: Boolean = false,
    val preciseLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val activityRecognitionGranted: Boolean = false,
    val notificationsEnabled: Boolean = false,
)

data class DiagnosticsState(
    val environment: EnvironmentStats = EnvironmentStats(),
    val db: DbStats = DbStats(),
    val recorderRows: List<DebugRow> = emptyList(),
    val workerRows: List<WorkerDebugRow> = emptyList(),
    val logFiles: List<File> = emptyList(),
)

data class LogExportResult(
    val exported: Int,
    val failed: Int,
)

data class LogContent(
    val text: String = "",
    val truncated: Boolean = false,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val sampleDao: LocationSampleDao,
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val placeDao: PlaceDao,
    private val settingsRepository: SettingsRepository,
    private val recorderService: RecorderServiceController,
    private val workManager: WorkManager,
) : ViewModel() {
    val diagnostics = MutableStateFlow(DiagnosticsState())

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val recent = sampleDao.mostRecent()
        val settings = settingsRepository.settings.first()
        val recorder = recorderService.debugState.value
        val today = TimeBuckets.dayEpoch(System.currentTimeMillis())
        val workers = workRows(today)
        val environment = EnvironmentStats(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            locationServicesEnabled = appContext.getSystemService(LocationManager::class.java)
                ?.isLocationEnabled == true,
            preciseLocationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocationGranted = hasPermission(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ),
            activityRecognitionGranted = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION),
            notificationsEnabled = NotificationManagerCompat.from(appContext)
                .areNotificationsEnabled(),
        )
        val dbStats = DbStats(
            samples = sampleDao.count(),
            excluded = sampleDao.excludedCount(),
            visits = visitDao.count(),
            trips = tripDao.count(),
            places = placeDao.count(),
            lastFix = recent?.let {
                "${TimeBuckets.localDate(it.dayEpoch)} ${Format.time(it.timestampMs)} · ${
                    appContext.getString(
                        it.devicePhysicalState.labelRes
                    )
                } · ±${it.accuracy?.toInt() ?: "?"}m"
            } ?: "—",
        )
        diagnostics.value = DiagnosticsState(
            environment = environment,
            db = dbStats,
            recorderRows = listOf(
                DebugRow(
                    R.string.diag_tracking_enabled,
                    appContext.getString(
                        if (settings.trackingEnabled) {
                            R.string.diag_status_enabled
                        } else {
                            R.string.diag_status_disabled
                        },
                    ),
                ),
                DebugRow(
                    R.string.diag_foreground_service_running,
                    appContext.getString(
                        if (recorder.isRecording) {
                            R.string.diag_status_enabled
                        } else {
                            R.string.diag_status_disabled
                        },
                    ),
                ),
                DebugRow(R.string.diag_recorder_state, recorder.state.name),
                DebugRow(
                    R.string.diag_motion_state,
                    appContext.getString(recorder.displayState.labelRes),
                ),
                DebugRow(
                    R.string.diag_power_profile,
                    recorder.profile?.name ?: settings.powerProfile.name,
                ),
                DebugRow(
                    R.string.diag_recorder_updated,
                    recorder.updatedAtMs?.let { Format.time(it) } ?: "—",
                ),
                DebugRow(
                    R.string.diag_last_service_detail,
                    recorder.lastStartError ?: "—",
                ),
            ),
            workerRows = workers,
            logFiles = logFiles(),
        )
    }

    fun logFiles(): List<File> = AppLog.sessionFiles()

    suspend fun deleteLog(file: File): Boolean {
        val deleted = withContext(Dispatchers.IO) { AppLog.deleteSessionFile(file) }
        diagnostics.value = diagnostics.value.copy(logFiles = logFiles())
        return deleted
    }

    suspend fun deleteAllLogs(): Int {
        val deleted = withContext(Dispatchers.IO) { AppLog.deleteSessionFiles() }
        diagnostics.value = diagnostics.value.copy(logFiles = logFiles())
        return deleted
    }

    suspend fun exportLogsToFolder(treeUri: Uri): LogExportResult = withContext(Dispatchers.IO) {
        exportSessionLogs(appContext, treeUri, logFiles())
    }

    suspend fun readLog(file: File): LogContent = withContext(Dispatchers.IO) {
        runCatching {
            val text = file.readText()
            LogContent(
                text = text.takeLast(MAX_LOG_VIEW_CHARS),
                truncated = text.length > MAX_LOG_VIEW_CHARS,
            )
        }.getOrElse {
            LogContent(text = appContext.getString(R.string.diag_log_read_failed, file.name))
        }
    }

    private suspend fun workRows(today: Long): List<WorkerDebugRow> = withContext(Dispatchers.IO) {
        val names = listOf(
            R.string.diag_worker_timeline_delayed to WorkScheduler.timelineMaintenanceWorkName(today),
            R.string.diag_worker_timeline_now to WorkScheduler.timelineMaintenanceNowWorkName(today),
            R.string.diag_worker_timeline_periodic to WorkScheduler.WORK_TIMELINE_PERIODIC,
            R.string.diag_worker_recording_watchdog to WorkScheduler.WORK_RECORDING_WATCHDOG,
            R.string.diag_worker_backup_periodic to WorkScheduler.WORK_BACKUP,
            R.string.diag_worker_backup_now to WorkScheduler.WORK_BACKUP_NOW,
            R.string.diag_worker_api_access to WorkScheduler.WORK_API_ACCESS,
        )
        names.map { (labelRes, name) ->
            val infos = runCatching { workManager.getWorkInfosForUniqueWork(name).get() }
                .getOrDefault(emptyList())
            // Unique work can retain finished history. Prefer its sole active request; attempt
            // count does not indicate recency and could otherwise select an obsolete retry.
            val info = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
            WorkerDebugRow(
                labelRes = labelRes,
                state = info?.state?.name ?: "NONE",
                attempts = info?.runAttemptCount ?: 0,
                id = info?.id?.toString()?.take(8) ?: "—",
            )
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

}

/** On-device diagnostics: live DB/recorder/worker stats and a shareable session-log browser. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var openFile by remember { mutableStateOf<File?>(null) }
    var content by remember { mutableStateOf(LogContent()) }
    var contentRefreshKey by remember { mutableIntStateOf(0) }
    var deleteFile by remember { mutableStateOf<File?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val exportLogs = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = viewModel.exportLogsToFolder(uri)
            val message = when {
                result.exported > 0 && result.failed == 0 ->
                    resources.getQuantityString(
                        R.plurals.diag_export_logs_done,
                        result.exported,
                        result.exported,
                    )

                result.exported > 0 ->
                    resources.getQuantityString(
                        R.plurals.diag_export_logs_partial,
                        result.exported,
                        result.exported,
                        result.failed,
                    )

                result.exported == 0 && result.failed == 0 ->
                    resources.getString(R.string.diag_export_logs_none)

                else ->
                    resources.getString(R.string.diag_export_logs_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(openFile, contentRefreshKey) {
        openFile?.let {
            content = LogContent()
            content = viewModel.readLog(it)
        }
    }

    FullScreenDialog(onDismiss = onDismiss) { requestClose ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.diagnostics_title)) },
                    navigationIcon = {
                        IconButton(onClick = { requestClose(onDismiss) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh_diagnostics)
                            )
                        }
                        IconButton(
                            onClick = { confirmDeleteAll = true },
                            enabled = diagnostics.logFiles.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.cd_delete_logs)
                            )
                        }
                        IconButton(
                            onClick = { exportLogs.launch(null) },
                            enabled = diagnostics.logFiles.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = stringResource(R.string.cd_export_logs)
                            )
                        }
                    },
                )
            },
        ) { padding ->
            val files = diagnostics.logFiles
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                item {
                    val environment = diagnostics.environment
                    DebugCard(stringResource(R.string.diag_app_and_system)) {
                        StatRow(
                            stringResource(R.string.diag_app_version),
                            environment.appVersion,
                        )
                        StatRow(
                            stringResource(R.string.diag_android_version),
                            environment.androidVersion,
                        )
                        StatRow(stringResource(R.string.diag_device), environment.device)
                        StatRow(
                            stringResource(R.string.diag_location_services),
                            enabledStatus(environment.locationServicesEnabled),
                        )
                        StatRow(
                            stringResource(R.string.diag_precise_location_permission),
                            permissionStatus(environment.preciseLocationGranted),
                        )
                        StatRow(
                            stringResource(R.string.diag_background_location_permission),
                            permissionStatus(environment.backgroundLocationGranted),
                        )
                        StatRow(
                            stringResource(R.string.perm_activity_title),
                            permissionStatus(environment.activityRecognitionGranted),
                        )
                        StatRow(
                            stringResource(R.string.diag_notifications),
                            enabledStatus(environment.notificationsEnabled),
                        )
                    }
                }
                item {
                    DebugCard(stringResource(R.string.diag_recorder)) {
                        diagnostics.recorderRows.forEach { row ->
                            StatRow(
                                stringResource(row.labelRes),
                                row.value,
                            )
                        }
                    }
                }
                item {
                    val stats = diagnostics.db
                    DebugCard(stringResource(R.string.diag_internal_data)) {
                        StatRow(
                            stringResource(R.string.diag_location_samples),
                            stringResource(
                                R.string.diag_location_samples_value,
                                stats.samples,
                                stats.excluded,
                            ),
                        )
                        StatRow(stringResource(R.string.diag_visits), stats.visits.toString())
                        StatRow(stringResource(R.string.diag_trips), stats.trips.toString())
                        StatRow(stringResource(R.string.diag_places), stats.places.toString())
                        StatRow(stringResource(R.string.diag_latest_sample), stats.lastFix)
                    }
                }
                item {
                    DebugCard(stringResource(R.string.diag_workers)) {
                        diagnostics.workerRows.forEach {
                            StatRow(
                                stringResource(it.labelRes),
                                stringResource(
                                    R.string.diag_worker_summary,
                                    it.state,
                                    it.attempts,
                                    it.id,
                                ),
                            )
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.diag_session_logs),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(files, key = { it.name }) { f ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { openFile = f },
                                onLongClick = { deleteFile = f },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${f.length() / 1024} KB · ${fileTime(f, resources.configuration.locales[0])}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
                if (files.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.diag_no_logs),
                            Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    openFile?.let { file ->
        LogFileDialog(
            file = file,
            content = content,
            onDismiss = { openFile = null },
            onRefresh = { contentRefreshKey++ },
            onShare = { shareLog(context, file) },
        )
    }

    deleteFile?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteFile = null },
            title = { Text(stringResource(R.string.diag_delete_log_title)) },
            text = { Text(stringResource(R.string.diag_delete_log_message, file.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val deleted = viewModel.deleteLog(file)
                            deleteFile = null
                            Toast.makeText(
                                context,
                                if (deleted) {
                                    R.string.diag_delete_log_done
                                } else {
                                    R.string.diag_delete_log_failed
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFile = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.diag_delete_all_logs_title)) },
            text = { Text(stringResource(R.string.diag_delete_all_logs_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val deleted = viewModel.deleteAllLogs()
                            confirmDeleteAll = false
                            val message = if (deleted > 0) {
                                resources.getQuantityString(
                                    R.plurals.diag_delete_all_logs_done,
                                    deleted,
                                    deleted,
                                )
                            } else {
                                resources.getString(R.string.diag_delete_all_logs_none)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFileDialog(
    file: File,
    content: LogContent,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
) {
    FullScreenDialog(onDismiss = onDismiss, dim = false) { requestClose ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(file.name) },
                    navigationIcon = {
                        IconButton(onClick = { requestClose(onDismiss) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.cd_close_log)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh_log)
                            )
                        }
                        IconButton(onClick = onShare) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = stringResource(R.string.cd_share_log)
                            )
                        }
                    },
                )
            },
        ) { padding ->
            val emptyText = stringResource(R.string.diag_log_empty)
            val truncatedText = pluralStringResource(
                R.plurals.diag_log_truncated,
                MAX_LOG_VIEW_CHARS,
                MAX_LOG_VIEW_CHARS,
            )
            val displayedContent = when {
                content.truncated -> "$truncatedText\n\n${content.text}"
                content.text.isEmpty() -> emptyText
                else -> content.text
            }
            SelectionContainer(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Text(
                    displayedContent,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun DebugCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun enabledStatus(enabled: Boolean): String = stringResource(
    if (enabled) R.string.diag_status_enabled else R.string.diag_status_disabled,
)

@Composable
private fun permissionStatus(granted: Boolean): String = stringResource(
    if (granted) R.string.diag_permission_granted else R.string.diag_permission_not_granted,
)

private fun fileTime(f: File, locale: Locale): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        .format(Date(f.lastModified()))

private fun exportSessionLogs(context: Context, treeUri: Uri, files: List<File>): LogExportResult {
    if (files.isEmpty()) return LogExportResult(exported = 0, failed = 0)

    val resolver = context.contentResolver
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { resolver.takePersistableUriPermission(treeUri, flags) }

    val root = DocumentFile.fromTreeUri(context, treeUri)
        ?.takeIf { it.isDirectory && it.canWrite() }
        ?: return LogExportResult(exported = 0, failed = files.size)

    var exported = 0
    var failed = 0
    files.forEach { source ->
        val ok = runCatching {
            root.listFiles()
                .filter { it.name == source.name }
                .forEach { it.delete() }

            val target = root.createFile("text/plain", source.name)
                ?: error("could not create ${source.name}")
            resolver.openOutputStream(target.uri, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("could not open ${source.name}")
        }.isSuccess
        if (ok) exported++ else failed++
    }
    return LogExportResult(exported = exported, failed = failed)
}

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
    context.startActivity(
        android.content.Intent.createChooser(
            intent,
            context.getString(R.string.share_logs_chooser)
        )
    )
}

/** Share exactly one selected session log via a FileProvider content URI. */
fun shareLog(context: Context, file: File) {
    val authority = "${context.packageName}.fileprovider"
    val uri =
        runCatching { androidx.core.content.FileProvider.getUriForFile(context, authority, file) }
            .getOrNull() ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(
            intent,
            context.getString(R.string.share_log_chooser, file.name)
        )
    )
}
