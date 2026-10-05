package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.domain.TimedRoutePoint
import net.extrawdw.apps.locationhistory.domain.TripPlayback

/** Both measurements share elapsed time and inspection, with independent vertical axes. */
@Composable
internal fun ActivityMetricsChart(
    playback: TripPlayback,
    startMs: Long,
    endMs: Long,
    progressTime: () -> Long,
    onProgressTimeChange: (Long) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val formatAltitude = remember(context, configuration) { Format.altitudeFormatter(context) }
    val formatSpeed = remember(context, configuration) { Format.speedFormatter(context) }
    val dark = isSystemInDarkTheme()
    val altitudeColor = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
    val speedColor = if (dark) Color(0xFF90CAF9) else Color(0xFF1565C0)
    val altitudeLabel = stringResource(R.string.activity_altitude)
    val speedLabel = stringResource(R.string.activity_speed)
    val altitude = remember(playback, altitudeColor, altitudeLabel, formatAltitude) {
        MetricSeries(altitudeLabel, altitudeColor, playback.altitudeSegments,
            { it.altitudeMeters!! }, playback::altitudeAt, formatAltitude)
    }
    val speed = remember(playback, speedColor, speedLabel, formatSpeed) {
        MetricSeries(speedLabel, speedColor, playback.speedSegments,
            { it.speedMetersPerSecond!! }, playback::speedAt, formatSpeed, zeroBaseline = true)
    }
    var showAltitude by rememberSaveable(startMs, endMs) { mutableStateOf(true) }
    var showSpeed by rememberSaveable(startMs, endMs) { mutableStateOf(true) }
    val visible = remember(showAltitude, showSpeed, altitude, speed) {
        listOfNotNull(altitude.takeIf { showAltitude && it.available }, speed.takeIf { showSpeed && it.available })
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showAltitude || showSpeed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (showSpeed) MetricAtSelection(speed, progressTime, Modifier.weight(1f))
                if (showAltitude) MetricAtSelection(altitude, progressTime, Modifier.weight(1f))
            }
            Text(stringResource(R.string.activity_graph_source), style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
        if (showAltitude && !altitude.available) {
            Text(stringResource(R.string.activity_altitude_unavailable), style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
        if (showSpeed && !speed.available) {
            Text(stringResource(R.string.activity_speed_unavailable), style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
        if (visible.isNotEmpty()) {
            val durationMs = (endMs - startMs).coerceAtLeast(0)
            val description = stringResource(R.string.activity_graph)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showAltitude && altitude.available) MetricAxis(altitude)
                Column(Modifier.weight(1f)) {
                    Spacer(
                        Modifier.fillMaxWidth().height(144.dp)
                            .semantics {
                                contentDescription = description
                                stateDescription = visible.joinToString(" · ") { "${it.label}: ${it.valueAt(progressTime())?.let(it.format) ?: "—"}" }
                                progressBarRangeInfo = ProgressBarRangeInfo(
                                    ((progressTime() - startMs) / 1000f).coerceIn(0f, durationMs / 1000f),
                                    0f..(durationMs / 1000f),
                                )
                                setProgress { seconds ->
                                    onProgressTimeChange(startMs + (seconds * 1000).toLong().coerceIn(0, durationMs))
                                    true
                                }
                            }
                            .pointerInput(startMs, endMs, onProgressTimeChange) {
                                detectTapGestures(onPress = { position ->
                                    val fraction = ((position.x - 4.dp.toPx()) / (size.width - 8.dp.toPx()).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    onProgressTimeChange(startMs + (fraction * durationMs).toLong())
                                })
                            }
                            .pointerInput(startMs, endMs, onProgressTimeChange) {
                                detectHorizontalDragGestures { change, _ ->
                                    val fraction = ((change.position.x - 4.dp.toPx()) / (size.width - 8.dp.toPx()).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    onProgressTimeChange(startMs + (fraction * durationMs).toLong())
                                }
                            }
                            .drawWithCache {
                                val inset = 4.dp.toPx()
                                fun x(time: Long) = inset +
                                    ((time - startMs).toDouble() / durationMs.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat() *
                                    (size.width - 2 * inset)
                                fun y(series: MetricSeries, value: Double) = inset +
                                    ((series.maximum - value) / (series.maximum - series.minimum)).toFloat() * (size.height - 2 * inset)
                                val paths = visible.associateWith { series ->
                                    series.segments.filter { it.size > 1 }.map { segment ->
                                        Path().apply {
                                            segment.forEachIndexed { index, point ->
                                                val px = x(point.timestampMs)
                                                val py = y(series, series.value(point))
                                                if (index == 0) moveTo(px, py) else lineTo(px, py)
                                            }
                                        }
                                    }
                                }
                                val isolated = visible.associateWith { series ->
                                    series.segments.filter { it.size == 1 }.map { segment ->
                                        segment.single().let { Offset(x(it.timestampMs), y(series, series.value(it))) }
                                    }
                                }
                                val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                                onDrawBehind {
                                    for (fraction in listOf(0f, 0.5f, 1f)) {
                                        val py = inset + (size.height - 2 * inset) * fraction
                                        drawLine(gridColor, Offset(inset, py), Offset(size.width - inset, py), 1.dp.toPx())
                                    }
                                    val time = progressTime()
                                    val px = x(time)
                                    drawLine(gridColor, Offset(px, inset), Offset(px, size.height - inset), 1.dp.toPx(), pathEffect = dash)
                                    visible.forEach { series ->
                                        paths.getValue(series).forEach { drawPath(it, series.color, style = stroke) }
                                        isolated.getValue(series).forEach { drawCircle(series.color, 2.dp.toPx(), it) }
                                        series.valueAt(time)?.let { drawCircle(series.color, 4.dp.toPx(), Offset(px, y(series, it))) }
                                    }
                                }
                            },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(Format.elapsed(0), style = MaterialTheme.typography.labelSmall, color = labelColor)
                        Text(Format.elapsed(durationMs), style = MaterialTheme.typography.labelSmall, color = labelColor)
                    }
                }
                if (showSpeed && speed.available) MetricAxis(speed)
            }
        }
        MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                checked = showSpeed, onCheckedChange = { showSpeed = it },
                shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text(speedLabel) },
            )
            SegmentedButton(
                checked = showAltitude, onCheckedChange = { showAltitude = it },
                shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text(altitudeLabel) },
            )
        }
    }
}

private class MetricSeries(
    val label: String,
    val color: Color,
    val segments: List<List<TimedRoutePoint>>,
    val value: (TimedRoutePoint) -> Double,
    val valueAt: (Long) -> Double?,
    val format: (Double) -> String,
    zeroBaseline: Boolean = false,
) {
    val available = segments.isNotEmpty()
    private val readings = segments.asSequence().flatten().map(value)
    private val low = readings.minOrNull() ?: 0.0
    private val high = readings.maxOrNull() ?: 0.0
    // Flat and isolated readings still need a valid scale; speed always starts at zero.
    val minimum = if (zeroBaseline) 0.0 else if (high - low < 2) (low + high) / 2 - 1 else low
    val maximum = if (zeroBaseline) high.coerceAtLeast(1.0) else if (high - low < 2) minimum + 2 else high
}

@Composable
private fun MetricAxis(series: MetricSeries) {
    Column(Modifier.height(144.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(series.format(series.maximum), style = MaterialTheme.typography.labelSmall, color = series.color)
        Text(series.format(series.minimum), style = MaterialTheme.typography.labelSmall, color = series.color)
    }
}

/** Read scrubbing state only in the value labels and draw phase, keeping the paths cached. */
@Composable
private fun MetricAtSelection(series: MetricSeries, selectedTime: () -> Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(series.label, style = MaterialTheme.typography.labelMedium, color = series.color)
        Text(series.valueAt(selectedTime())?.let(series.format) ?: "—", style = MaterialTheme.typography.titleMedium, color = series.color)
    }
}
