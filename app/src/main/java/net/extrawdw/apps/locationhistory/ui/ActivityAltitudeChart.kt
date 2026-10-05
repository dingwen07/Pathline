package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import net.extrawdw.apps.locationhistory.domain.TripPlayback

/** The horizontal axis and cursor use elapsed time, matching the Activity progress slider. */
@Composable
internal fun ActivityAltitudeChart(
    playback: TripPlayback,
    startMs: Long,
    endMs: Long,
    progressTime: () -> Long,
    onProgressTimeChange: (Long) -> Unit,
    color: Color,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val formatAltitude = remember(context, configuration) { Format.altitudeFormatter(context) }
    val segments = playback.altitudeSegments
    val range = remember(segments) {
        val readings = segments.flatten().mapNotNull { it.altitudeMeters }
        readings.minOrNull()?.let { it to readings.max() }
    }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.activity_altitude), style = MaterialTheme.typography.titleMedium)
            if (range != null) {
                AltitudeAtSelection(playback, progressTime, formatAltitude)
            }
        }
        Text(
            stringResource(if (range == null) R.string.activity_altitude_unavailable else R.string.activity_altitude_source),
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
        )
        if (range != null) {
            // A flat or single-reading profile still needs a nonzero vertical scale.
            val minimum = if (range.second - range.first < 2.0) (range.first + range.second) / 2 - 1 else range.first
            val maximum = if (range.second - range.first < 2.0) minimum + 2 else range.second
            val durationMs = (endMs - startMs).coerceAtLeast(0)
            val description = stringResource(
                R.string.activity_altitude_range,
                formatAltitude(range.first), formatAltitude(range.second),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    Modifier.height(144.dp).padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatAltitude(maximum), style = MaterialTheme.typography.labelSmall, color = labelColor)
                    Text(formatAltitude(minimum), style = MaterialTheme.typography.labelSmall, color = labelColor)
                }
                Column(Modifier.weight(1f)) {
                    Spacer(
                        Modifier.fillMaxWidth().height(144.dp)
                            .semantics {
                                contentDescription = description
                                stateDescription = playback.altitudeAt(progressTime())?.let(formatAltitude) ?: "—"
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
                                fun y(altitude: Double) = inset +
                                    ((maximum - altitude) / (maximum - minimum)).toFloat() * (size.height - 2 * inset)
                                val paths = segments.filter { it.size > 1 }.map { segment ->
                                    Path().apply {
                                        segment.forEachIndexed { index, point ->
                                            val px = x(point.timestampMs)
                                            val py = y(point.altitudeMeters!!)
                                            if (index == 0) moveTo(px, py) else lineTo(px, py)
                                        }
                                    }
                                }
                                val isolated = segments.filter { it.size == 1 }.map { segment ->
                                    segment.single().let { Offset(x(it.timestampMs), y(it.altitudeMeters!!)) }
                                }
                                val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                                onDrawBehind {
                                    for (fraction in listOf(0f, 0.5f, 1f)) {
                                        val py = inset + (size.height - 2 * inset) * fraction
                                        drawLine(gridColor, Offset(inset, py), Offset(size.width - inset, py), 1.dp.toPx())
                                    }
                                    paths.forEach { drawPath(it, color, style = stroke) }
                                    isolated.forEach { drawCircle(color, 2.dp.toPx(), it) }
                                    val time = progressTime()
                                    val px = x(time)
                                    drawLine(
                                        color.copy(alpha = 0.5f), Offset(px, inset), Offset(px, size.height - inset),
                                        1.dp.toPx(), pathEffect = dash,
                                    )
                                    playback.altitudeAt(time)?.let { drawCircle(color, 4.dp.toPx(), Offset(px, y(it))) }
                                }
                            },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(Format.elapsed(0), style = MaterialTheme.typography.labelSmall, color = labelColor)
                        Text(Format.elapsed(durationMs), style = MaterialTheme.typography.labelSmall, color = labelColor)
                    }
                }
            }
        }
    }
}

/** Read rapidly changing progress only in the label and draw phase, leaving the cached path alone. */
@Composable
private fun AltitudeAtSelection(playback: TripPlayback, progressTime: () -> Long, formatAltitude: (Double) -> String) {
    Text(
        playback.altitudeAt(progressTime())?.let(formatAltitude) ?: "—",
        style = MaterialTheme.typography.titleMedium,
    )
}
