package net.extrawdw.apps.locationhistory.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.extrawdw.apps.locationhistory.data.db.ApiAccessEventEntity
import net.extrawdw.apps.locationhistory.data.repo.ApiAccessRepository
import net.extrawdw.apps.locationhistory.data.repo.ApiScope
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** One app row in the "Apps with access" list, also used to decorate timeline rows. */
data class ApiAppRow(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val declared: List<ApiScope>,
    val granted: List<ApiScope>,
    val lastAccessMs: Long?,
    val reads: Int,
    val installed: Boolean,
)

@HiltViewModel
class ApiAccessViewModel @Inject constructor(
    private val repo: ApiAccessRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    // The in-app history only covers a recent window; everything is still kept for "export all".
    private val windowStartMs = System.currentTimeMillis() - HISTORY_WINDOW_MS

    /** The access timeline for the last 7 days (capped), most-recent first, updated live. */
    val events: StateFlow<List<ApiAccessEventEntity>> =
        repo.observeEventsSince(windowStartMs, MAX_EVENTS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _apps = MutableStateFlow<List<ApiAppRow>>(emptyList())
    val apps: StateFlow<List<ApiAppRow>> = _apps.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init { refresh() }

    /** Recompute the app list + grant state from the packages seen in the log. */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val packages = repo.loggedPackages()
                val declared = repo.declaredApps(packages)
                val lastAccess = repo.observeAppLastAccess().first().associateBy { it.packageName }
                _apps.value = withContext(Dispatchers.IO) {
                    declared.map { d ->
                        ApiAppRow(
                            packageName = d.packageName,
                            label = d.label,
                            icon = d.iconPng?.let(::decodeIcon),
                            declared = d.declared,
                            granted = d.granted,
                            lastAccessMs = lastAccess[d.packageName]?.lastMs,
                            reads = lastAccess[d.packageName]?.reads ?: 0,
                            installed = d.installed,
                        )
                    }
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Clear the per-app notification back-off so the next read from each app alerts immediately. */
    fun resetNotificationBackoff() {
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.resetReadNotificationBackoff() } }
    }

    private fun decodeIcon(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap() }.getOrNull()

    /** Write the entire log to a CSV in filesDir/logs (served by the FileProvider) and return it. */
    suspend fun exportCsv(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val events = repo.allEvents()
            val labels = _apps.value.associate { it.packageName to it.label }
            val iso = DateTimeFormatter.ISO_INSTANT
            fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            val sb = StringBuilder(
                "timestamp,app_package,app_name,data_type,requested_start,requested_end,row_count,group,denied_permission\n"
            )
            for (e in events) {
                sb.append(esc(iso.format(Instant.ofEpochMilli(e.timestampMs)))).append(',')
                    .append(esc(e.packageName)).append(',')
                    .append(esc(labels[e.packageName] ?: e.packageName)).append(',')
                    .append(esc(e.dataType)).append(',')
                    .append(esc(iso.format(Instant.ofEpochMilli(e.startMs)))).append(',')
                    .append(esc(iso.format(Instant.ofEpochMilli(e.endMs)))).append(',')
                    .append(e.rowCount).append(',')
                    .append(e.groupId?.toString() ?: "").append(',')
                    .append(esc(e.deniedPermission ?: "")).append('\n')
            }
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            File(dir, "pathline-api-access.csv").apply { writeText(sb.toString()) }
        }.getOrNull()
    }

    private companion object {
        const val HISTORY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_EVENTS = 1000
    }
}
