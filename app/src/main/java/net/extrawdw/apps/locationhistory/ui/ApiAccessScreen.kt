package net.extrawdw.apps.locationhistory.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.data.db.ApiAccessEventEntity
import net.extrawdw.apps.locationhistory.data.repo.ApiScope
import java.io.File
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiAccessScreen(onBack: () -> Unit, viewModel: ApiAccessViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val infoByPackage = remember(apps) { apps.associateBy { it.packageName } }
    // Expand state for aggregated access groups, keyed by "package|groupId".
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Whether Pathline can actually alert the user about access — drives the privacy banner.
    var notificationsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsGranted = it }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.api_access_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.resetNotificationBackoff()
                        Toast.makeText(context, R.string.api_access_backoff_reset, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = stringResource(R.string.api_access_reset_backoff))
                    }
                    IconButton(onClick = {
                        scope.launch { viewModel.exportCsv()?.let { shareCsv(context, it) } }
                    }) {
                        Icon(Icons.Filled.IosShare, contentDescription = stringResource(R.string.api_access_export))
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (!notificationsGranted) {
                    item {
                        NotificationsOffBanner(
                            onEnable = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            onOpenSettings = { openNotificationSettings(context) },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }

                item {
                    IntroCard(stringResource(R.string.api_access_intro))
                    Spacer(Modifier.height(16.dp))
                }

                // Apps with access (resolved only from apps that have actually read data).
                item { SectionHeader(stringResource(R.string.api_access_apps_header)) }
                if (apps.isEmpty()) {
                    item { EmptyText(stringResource(R.string.api_access_no_apps)) }
                } else {
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(app = app, onClick = { openAppSettings(context, app.packageName) })
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    SectionHeader(stringResource(R.string.api_access_activity_header))
                }
                if (events.isEmpty()) {
                    item { EmptyText(stringResource(R.string.api_access_no_activity)) }
                } else {
                    val zone = ZoneId.systemDefault()
                    val grouped = events.groupBy { Instant.ofEpochMilli(it.timestampMs).atZone(zone).toLocalDate() }
                    grouped.forEach { (date, dayEvents) ->
                        item(key = "h-$date") { DayHeader(date, zone) }
                        item(key = "events-$date") {
                            DayEventGroup(
                                events = dayEvents,
                                infoByPackage = infoByPackage,
                                zone = zone,
                                expandedGroups = expandedGroups,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Privacy warning shown when notifications are off — Pathline then can't alert about data access. */
@Composable
private fun NotificationsOffBanner(onEnable: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.api_access_notif_off_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                stringResource(R.string.api_access_notif_off_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEnable) { Text(stringResource(R.string.api_access_notif_off_enable)) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.api_access_notif_settings)) }
            }
        }
    }
}

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun IntroCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DayHeader(date: LocalDate, zone: ZoneId) {
    val today = LocalDate.now(zone)
    val label = when (date) {
        today -> stringResource(R.string.api_access_today)
        today.minusDays(1) -> stringResource(R.string.api_access_yesterday)
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun AppIconCircle(icon: ImageBitmap?, size: Dp) {
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/**
 * Draws one shared connector for a day group. Keeping the dash path outside individual rows prevents
 * the dash phase from restarting at row boundaries, which caused doubled-up marks between icons.
 */
@Composable
private fun DayEventGroup(
    events: List<ApiAccessEventEntity>,
    infoByPackage: Map<String, ApiAppRow>,
    zone: ZoneId,
    expandedGroups: MutableMap<String, Boolean>,
) {
    val entries = remember(events) { buildDayEntries(events) }
    val iconCenters = remember(entries) { mutableStateMapOf<Long, Float>() }
    val iconSize = 32.dp
    val iconGap = 4.dp
    val color = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (entries.size >= 2) {
                    val x = 48.dp.toPx() + 20.dp.toPx()
                    val skip = iconSize.toPx() / 2f + iconGap.toPx()
                    val dotRadius = 1.5.dp.toPx()
                    val targetPitch = 11.dp.toPx()
                    entries.zipWithNext().forEach { (from, to) ->
                        val startCenter = iconCenters[from.anchorId] ?: return@forEach
                        val endCenter = iconCenters[to.anchorId] ?: return@forEach
                        val startY = startCenter + skip
                        val endY = endCenter - skip
                        val availableHeight = endY - startY
                        if (availableHeight <= dotRadius * 2f) return@forEach

                        val dashCount = max(1, (availableHeight / targetPitch).roundToInt())
                        val balancedGap = availableHeight / (dashCount + 1)
                        val firstDotCenter = startY + balancedGap
                        repeat(dashCount) { index ->
                            val dotCenterY = firstDotCenter + index * balancedGap
                            drawCircle(
                                color = color,
                                radius = dotRadius,
                                center = Offset(x, dotCenterY),
                            )
                        }
                    }
                }
        },
    ) {
        entries.forEach { entry ->
            when (entry) {
                is SingleEntry -> {
                    val e = entry.event
                    EventRow(
                        event = e,
                        appLabel = infoByPackage[e.packageName]?.label ?: e.packageName,
                        icon = infoByPackage[e.packageName]?.icon,
                        zone = zone,
                        onIconCenterMeasured = { centerY -> iconCenters[entry.anchorId] = centerY },
                    )
                }

                is GroupEntry -> {
                    val isExpanded = expandedGroups[entry.key] == true
                    GroupRow(
                        entry = entry,
                        appLabel = infoByPackage[entry.packageName]?.label ?: entry.packageName,
                        icon = infoByPackage[entry.packageName]?.icon,
                        zone = zone,
                        expanded = isExpanded,
                        onToggle = { expandedGroups[entry.key] = !isExpanded },
                        onIconCenterMeasured = { centerY -> iconCenters[entry.anchorId] = centerY },
                    )
                    if (isExpanded) {
                        entry.events.forEach { member -> GroupMemberRow(member, zone) }
                    }
                }
            }
        }
    }
}

/** One timeline entry: a single ungrouped read, or several reads aggregated under one batch group. */
private sealed interface DayEntry {
    val anchorId: Long
    val sortKey: Long
}

private data class SingleEntry(val event: ApiAccessEventEntity) : DayEntry {
    override val anchorId get() = event.id
    override val sortKey get() = event.timestampMs
}

private data class GroupEntry(
    val packageName: String,
    val groupId: Long,
    val events: List<ApiAccessEventEntity>,
) : DayEntry {
    val key get() = "$packageName|$groupId"
    override val anchorId get() = events.minOf { it.id }
    override val sortKey get() = events.maxOf { it.timestampMs }
}

/** Collapse same-(package, group) reads into one expandable entry; ungrouped reads stay individual. */
private fun buildDayEntries(events: List<ApiAccessEventEntity>): List<DayEntry> {
    val groups = linkedMapOf<String, MutableList<ApiAccessEventEntity>>()
    val out = ArrayList<DayEntry>()
    for (e in events) {
        val g = e.groupId
        if (g == null) {
            out.add(SingleEntry(e))
        } else {
            groups.getOrPut("${e.packageName}|$g") { mutableListOf() }.add(e)
        }
    }
    for ((_, list) in groups) {
        if (list.size == 1) {
            out.add(SingleEntry(list[0]))
        } else {
            out.add(GroupEntry(list[0].packageName, list[0].groupId!!, list.sortedByDescending { it.timestampMs }))
        }
    }
    return out.sortedByDescending { it.sortKey }
}

/** Collapsed header for an access group: app · "N requests · 6 visits, 2,636 samples" · expand chevron. */
@Composable
private fun GroupRow(
    entry: GroupEntry,
    appLabel: String,
    icon: ImageBitmap?,
    zone: ZoneId,
    expanded: Boolean,
    onToggle: () -> Unit,
    onIconCenterMeasured: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val iconCenterFromRowTop = with(density) { (8.dp + 32.dp / 2).toPx() }
    val time = remember(entry.sortKey) {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(entry.sortKey).atZone(zone))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 64.dp)
            .clickable(onClick = onToggle)
            .onGloballyPositioned { coordinates ->
                onIconCenterMeasured(coordinates.positionInParent().y + iconCenterFromRowTop)
            },
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp).padding(top = 14.dp),
        )
        TimelineIconColumn(icon = icon)
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 10.dp, bottom = 12.dp)) {
            Text(appLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                groupSummary(entry.events),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, end = 4.dp),
        )
    }
}

/** A single request inside an expanded group — indented under the group, no icon/connector. */
@Composable
private fun GroupMemberRow(event: ApiAccessEventEntity, zone: ZoneId) {
    val time = remember(event.timestampMs) {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(event.timestampMs).atZone(zone))
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 88.dp, top = 2.dp, bottom = 6.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            time,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.api_access_event_subtitle,
                event.rowCount,
                dataTypeLabel(event.dataType),
                formatRequestedRange(event.startMs, event.endMs, zone),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "3 requests · 6 visits, 8 trips, 2,636 location samples" — counts use the max per type. */
@Composable
private fun groupSummary(events: List<ApiAccessEventEntity>): String {
    val nf = remember { NumberFormat.getInstance() }
    val maxByType = events.groupBy { it.dataType }.mapValues { (_, e) -> e.maxOf { it.rowCount } }
    val parts = listOfNotNull(
        maxByType["visits"]?.let { "${nf.format(it.toLong())} ${stringResource(R.string.api_data_visits)}" },
        maxByType["trips"]?.let { "${nf.format(it.toLong())} ${stringResource(R.string.api_data_trips)}" },
        maxByType["samples"]?.let { "${nf.format(it.toLong())} ${stringResource(R.string.api_data_samples)}" },
    )
    val requests = stringResource(R.string.api_access_group_requests, events.size)
    return if (parts.isEmpty()) requests
    else "$requests · " + parts.joinToString(stringResource(R.string.api_notify_data_separator))
}

/** The leading column of a timeline row: the app icon aligned with the shared day connector. */
@Composable
private fun TimelineIconColumn(icon: ImageBitmap?) {
    val iconSize = 32.dp
    val iconTop = 8.dp
    Box(
        Modifier.width(40.dp).fillMaxHeight(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(iconTop))
            AppIconCircle(icon, iconSize)
        }
    }
}

/** Native-style timeline row: time · app icon with dashed connector · app name + requested range. */
@Composable
private fun EventRow(
    event: ApiAccessEventEntity,
    appLabel: String,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    zone: ZoneId,
    onIconCenterMeasured: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val iconCenterFromRowTop = with(density) { (8.dp + 32.dp / 2).toPx() }
    val time = remember(event.timestampMs) {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(event.timestampMs).atZone(zone))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 64.dp)
            .onGloballyPositioned { coordinates ->
                onIconCenterMeasured(coordinates.positionInParent().y + iconCenterFromRowTop)
            },
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp).padding(top = 14.dp),
        )
        TimelineIconColumn(icon = icon)
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 10.dp, bottom = 12.dp)) {
            Text(appLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(
                    R.string.api_access_event_subtitle,
                    event.rowCount,
                    dataTypeLabel(event.dataType),
                    formatRequestedRange(event.startMs, event.endMs, zone),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppRow(app: ApiAppRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconCircle(app.icon, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val subtitle = when {
                !app.installed -> stringResource(R.string.api_access_not_installed)
                app.lastAccessMs != null -> stringResource(
                    R.string.api_access_last_read,
                    DateUtils.getRelativeTimeSpanString(app.lastAccessMs).toString(),
                )
                else -> stringResource(R.string.api_access_never_read)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ScopeChips(granted = app.granted, declared = app.declared)
        }
    }
}

@Composable
private fun ScopeChips(granted: List<ApiScope>, declared: List<ApiScope>) {
    if (declared.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        if (granted.isEmpty()) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.api_access_revoked), style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(disabledLabelColor = labelColor),
            )
        } else {
            granted.forEach { scope ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(scope.labelRes), style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(disabledLabelColor = labelColor),
                )
            }
        }
    }
}

@Composable
private fun dataTypeLabel(dataType: String): String = stringResource(
    when (dataType) {
        "visits" -> R.string.api_data_visits
        "trips" -> R.string.api_data_trips
        "samples" -> R.string.api_data_samples
        else -> R.string.api_data_unknown
    },
)

/** "Jun 1 – Jun 7" (omits the year unless it differs from the current one). */
private fun formatRequestedRange(startMs: Long, endMs: Long, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(startMs).atZone(zone)
    val end = Instant.ofEpochMilli(endMs).atZone(zone)
    val thisYear = LocalDate.now(zone).year
    fun fmt(d: ZonedDateTime): String {
        val pattern = if (d.year == thisYear) "MMM d" else "MMM d, yyyy"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(d)
    }
    return if (start.toLocalDate() == end.toLocalDate()) fmt(start) else "${fmt(start)} – ${fmt(end)}"
}

private fun openAppSettings(context: Context, pkg: String) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun shareCsv(context: Context, file: File) {
    val authority = "${context.packageName}.fileprovider"
    val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.api_access_export_chooser)))
}
