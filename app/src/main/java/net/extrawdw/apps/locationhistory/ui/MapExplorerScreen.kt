package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.Polyline
import net.extrawdw.apps.locationhistory.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.android.awaitFrame
import androidx.compose.ui.graphics.Color as ComposeColor

private val SAMPLE_RED = ComposeColor(0xFFFF1744)
private val TRACK_RED = ComposeColor(0x66FF1744) // translucent
private const val DOT_ALPHA_NO_TRACK = 0.72f
private const val MERCATOR_TILE_SIZE = 256.0
private const val DOT_DP = 3f
private const val DOT_DP_NO_TRACK = 1.5f

@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun MapExplorerScreen(
    mapOnScreen: Boolean = true,
    viewModel: MapExplorerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val drawTrackCustom by viewModel.drawTrackCustom.collectAsStateWithLifecycle()
    val customStart by viewModel.customStart.collectAsStateWithLifecycle()
    val customEnd by viewModel.customEnd.collectAsStateWithLifecycle()

    val cameraPositionState = viewModel.cameraPositionState
    val showPicker = remember { mutableStateOf(false) }

    // Without a track line the dots stand on their own, so shrink them further to cut clutter.
    val density = LocalDensity.current
    val drawingTrack = state.trackPoints.size >= 2
    val dotSizePx =
        with(density) { (if (drawingTrack) DOT_DP else DOT_DP_NO_TRACK).dp.toPx() }
    val dotIndex = remember(state.dotPoints) { MapDotIndex(state.dotPoints) }
    val cameraPosition = cameraPositionState.position
    val cameraMoving = cameraPositionState.isMoving
    val overlayFrame = remember { mutableIntStateOf(0) }

    LaunchedEffect(cameraMoving) {
        while (cameraMoving) {
            awaitFrame()
            overlayFrame.intValue++
        }
    }

    // Open near the user's data (last recorded sample) instead of the global world view — only the
    // very first time the tab is shown, before any range's points have framed the camera.
    LaunchedEffect(Unit) {
        if (viewModel.cameraFramed) return@LaunchedEffect
        val target = viewModel.initialCameraTarget()
        if (!viewModel.cameraFramed && target != null) {
            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(target, 14f))
        }
    }

    // Fit the camera when the plotted dataset actually changes (range switch / fresh load). Skipped
    // on a plain tab re-entry, where the points are the same instance, so the user's zoom is kept.
    LaunchedEffect(state.dotPoints) {
        val pts = state.dotPoints
        if (pts.isEmpty() || viewModel.lastFittedPoints === pts) return@LaunchedEffect
        if (pts.size == 1) {
            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(pts.first(), 15f))
        } else {
            val bounds = LatLngBounds.builder().apply { pts.forEach { include(it) } }.build()
            runCatching {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngBounds(
                        bounds,
                        120
                    )
                )
            }
                .onFailure {
                    cameraPositionState.move(
                        CameraUpdateFactory.newLatLngZoom(
                            bounds.center,
                            14f
                        )
                    )
                }
        }
        viewModel.lastFittedPoints = pts
        viewModel.cameraFramed = true
    }

    // Drop the map's GPU surface when it isn't needed (backgrounded off-screen, or under memory
    // pressure). cameraPositionState lives in the ViewModel so the camera is restored on remount.
    val showMap = rememberMapComposed(onScreen = mapOnScreen)

    Box(Modifier.fillMaxSize()) {
        if (showMap) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                mapColorScheme = ComposeMapColorScheme.DARK,
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    rotationGesturesEnabled = false,
                    tiltGesturesEnabled = false
                ),
            ) {
                if (state.trackPoints.size >= 2) {
                    Polyline(points = state.trackPoints, color = TRACK_RED, width = 6f)
                }
            }

            DotCanvasOverlay(
                cameraPosition = cameraPosition,
                dotIndex = dotIndex,
                dotSizePx = dotSizePx,
                dotAlpha = if (drawingTrack) 1f else DOT_ALPHA_NO_TRACK,
                screenDensity = density.density,
                frameTick = overlayFrame.intValue,
                modifier = Modifier.fillMaxSize(),
            )
        }

        ControlBar(
            range = range,
            drawTrackCustom = drawTrackCustom,
            customStart = customStart,
            customEnd = customEnd,
            pointCount = state.totalCount,
            loading = state.loading,
            onSelectRange = { viewModel.selectRange(it) },
            onToggleTrack = { viewModel.setDrawTrackCustom(it) },
            onPickDates = { showPicker.value = true },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }

    if (showPicker.value) {
        DateRangePickerDialog(
            initialStart = customStart,
            initialEnd = customEnd,
            onConfirm = { startDay, endDay ->
                viewModel.setCustomRange(startDay, endDay)
                viewModel.selectRange(MapRange.CUSTOM)
                showPicker.value = false
            },
            onDismiss = { showPicker.value = false },
        )
    }
}

@Composable
private fun DotCanvasOverlay(
    cameraPosition: CameraPosition,
    dotIndex: MapDotIndex,
    dotSizePx: Float,
    dotAlpha: Float,
    screenDensity: Float,
    frameTick: Int,
    modifier: Modifier = Modifier,
) {
    if (dotIndex.isEmpty) return
    val radiusPx = (dotSizePx / 2f).coerceAtLeast(0.5f)
    val redrawTick = frameTick

    Canvas(modifier) {
        redrawTick
        if (!cameraPosition.zoom.isFinite()) return@Canvas
        val widthPx = size.width
        val heightPx = size.height
        if (widthPx <= 0f || heightPx <= 0f) return@Canvas

        val capCellPx = (radiusPx * 0.5f).coerceAtLeast(1f)
        val columns = ceil(widthPx / capCellPx).toInt().coerceAtLeast(1)
        val rows = ceil(heightPx / capCellPx).toInt().coerceAtLeast(1)
        val drawnCells = BooleanArray(columns * rows)
        val margin = radiusPx

        dotIndex.forEachVisible(cameraPosition, widthPx, heightPx, margin, screenDensity) { x, y ->
            if (x < -margin || x > widthPx + margin || y < -margin || y > heightPx + margin) {
                return@forEachVisible
            }

            val column = floor(x / capCellPx).toInt().coerceIn(0, columns - 1)
            val row = floor(y / capCellPx).toInt().coerceIn(0, rows - 1)
            val cell = row * columns + column
            if (drawnCells[cell]) return@forEachVisible
            drawnCells[cell] = true

            drawCircle(
                color = SAMPLE_RED.copy(alpha = dotAlpha),
                radius = radiusPx,
                center = Offset(x, y),
            )
        }
    }
}

private class MapDotIndex(points: List<LatLng>) {
    private val xs: DoubleArray
    private val ys: DoubleArray
    val isEmpty: Boolean
        get() = xs.isEmpty()

    init {
        val projected = points.mapNotNull { point ->
            val x = ((point.longitude + 180.0) / 360.0).mod(1.0)
            val sinLatitude = sin(Math.toRadians(point.latitude)).coerceIn(-0.9999, 0.9999)
            val y = 0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * Math.PI)
            if (x.isFinite() && y.isFinite()) x to y.coerceIn(0.0, 1.0) else null
        }
        xs = DoubleArray(projected.size)
        ys = DoubleArray(projected.size)
        for (i in projected.indices) {
            xs[i] = projected[i].first
            ys[i] = projected[i].second
        }
    }

    inline fun forEachVisible(
        cameraPosition: CameraPosition,
        widthPx: Float,
        heightPx: Float,
        marginPx: Float,
        screenDensity: Float,
        draw: (Float, Float) -> Unit,
    ) {
        val center = project(cameraPosition.target)
        val scale = MERCATOR_TILE_SIZE * screenDensity * 2.0.pow(cameraPosition.zoom.toDouble())
        val centerX = center.first * scale
        val centerY = center.second * scale
        val halfWidth = widthPx / 2.0
        val halfHeight = heightPx / 2.0
        val worldWidth = scale

        for (i in xs.indices) {
            var dx = xs[i] * scale - centerX
            if (dx > worldWidth / 2.0) dx -= worldWidth
            if (dx < -worldWidth / 2.0) dx += worldWidth
            if (dx < -halfWidth - marginPx || dx > halfWidth + marginPx) continue

            val dy = ys[i] * scale - centerY
            if (dy < -halfHeight - marginPx || dy > halfHeight + marginPx) continue

            draw((dx + halfWidth).toFloat(), (dy + halfHeight).toFloat())
        }
    }

    companion object {
        fun project(point: LatLng): Pair<Double, Double> {
            val x = ((point.longitude + 180.0) / 360.0).mod(1.0)
            val sinLatitude = sin(Math.toRadians(point.latitude)).coerceIn(-0.9999, 0.9999)
            val y = 0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * Math.PI)
            return x to y.coerceIn(0.0, 1.0)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlBar(
    range: MapRange,
    drawTrackCustom: Boolean,
    customStart: Long?,
    customEnd: Long?,
    pointCount: Int,
    loading: Boolean,
    onSelectRange: (MapRange) -> Unit,
    onToggleTrack: (Boolean) -> Unit,
    onPickDates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Size each segment to its content and let the row scroll horizontally on narrow
            // screens, so the selected checkmark + label stay centered instead of being squeezed.
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                MapRange.entries.forEachIndexed { index, r ->
                    SegmentedButton(
                        selected = r == range,
                        onClick = { if (r == MapRange.CUSTOM) onPickDates() else onSelectRange(r) },
                        shape = SegmentedButtonDefaults.itemShape(index, MapRange.entries.size),
                        label = { Text(stringResource(r.labelRes), maxLines = 1) },
                    )
                }
            }
            if (range == MapRange.CUSTOM) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        customRangeLabel(LocalContext.current, customStart, customEnd),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPickDates) { Text(stringResource(R.string.action_change)) }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.map_draw_track),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = drawTrackCustom, onCheckedChange = onToggleTrack)
                }
            }
            Text(
                if (loading) stringResource(R.string.map_loading) else pluralStringResource(
                    R.plurals.points_count,
                    pointCount,
                    pointCount
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerDialog(
    initialStart: Long?,
    initialEnd: Long?,
    onConfirm: (startDayEpoch: Long, endDayEpoch: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart?.let { dayEpochToUtcMillis(it) },
        initialSelectedEndDateMillis = initialEnd?.let { dayEpochToUtcMillis(it) },
    )
    // Full-screen dialog so the official Material date-range calendar has room for the weekday
    // header (S M T W T F S) and doesn't clip the Sunday column.
    FullScreenDialog(onDismiss = onDismiss) { requestClose ->
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { requestClose(onDismiss) }) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.weight(1f))
                val start = pickerState.selectedStartDateMillis
                val end = pickerState.selectedEndDateMillis
                Button(
                    onClick = {
                        if (start != null && end != null) {
                            requestClose {
                                onConfirm(
                                    utcMillisToDayEpoch(start),
                                    utcMillisToDayEpoch(end)
                                )
                            }
                        }
                    },
                    enabled = start != null && end != null,
                ) { Text(stringResource(R.string.action_plot)) }
            }
            DateRangePicker(
                state = pickerState, modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

/** Material's date picker works in UTC midnight; convert to/from a local-day epoch. */
private fun utcMillisToDayEpoch(utcMillis: Long): Long =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

private fun dayEpochToUtcMillis(dayEpoch: Long): Long =
    LocalDate.ofEpochDay(dayEpoch).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private val DAY_FMT = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun customRangeLabel(
    context: android.content.Context,
    startDay: Long?,
    endDay: Long?
): String {
    if (startDay == null || endDay == null) return context.getString(R.string.map_no_dates)
    val lo = minOf(startDay, endDay)
    val hi = maxOf(startDay, endDay)
    val start = LocalDate.ofEpochDay(lo).format(DAY_FMT)
    val end = LocalDate.ofEpochDay(hi).format(DAY_FMT)
    return if (lo == hi) start else context.getString(R.string.date_range, start, end)
}
