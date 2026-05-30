package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.domain.SegmentType

private val TYPE_OPTIONS: List<SegmentType> =
    listOf(SegmentType.Stationary) + TransportMode.MODEL_CLASSES.map { SegmentType.Moving(it) }

private fun SegmentType.label(): String = when (this) {
    SegmentType.Stationary -> "Stationary"
    is SegmentType.Moving -> mode.label
}

/**
 * Sample-level drift-correction editor, rendered **inside the bottom sheet** (not a modal) so the
 * map behind stays fully interactive (pan/zoom) while editing. A slider (+/- fine buttons) picks the
 * split sample; each side shows its editable type, sample count, time and duration. "Reclassify all"
 * assigns one type to the whole item. [onSplitIndexChange]/[onReclassifyType] drive the map preview.
 */
@Composable
fun SplitEditorPanel(
    samples: List<LocationSampleEntity>,
    initialType: SegmentType,
    onSplit: (index: Int, left: SegmentType, right: SegmentType) -> Unit,
    onConvert: (type: SegmentType) -> Unit,
    onCancel: () -> Unit,
    onSplitIndexChange: (Int?) -> Unit = {},
    onReclassifyType: (SegmentType?) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        if (samples.size < 2) {
            var whole by remember { mutableStateOf(initialType) }
            LaunchedEffect(whole) { onSplitIndexChange(null); onReclassifyType(whole) }
            Text("Not enough samples to split — reclassify the whole item.", style = MaterialTheme.typography.bodyMedium)
            TypePicker("Type", whole, Modifier.padding(top = 8.dp)) { whole = it }
            Button(onClick = { onConvert(whole) }, modifier = Modifier.padding(top = 12.dp)) { Text("Apply") }
            return@Column
        }

        var splitMode by remember { mutableStateOf(true) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(splitMode, { splitMode = true }, { Text("Split") })
            FilterChip(!splitMode, { splitMode = false }, { Text("Reclassify all") })
        }

        if (!splitMode) {
            var whole by remember { mutableStateOf(initialType) }
            LaunchedEffect(whole) { onSplitIndexChange(null); onReclassifyType(whole) }
            TypePicker("Type", whole, Modifier.padding(top = 16.dp)) { whole = it }
            Button(onClick = { onConvert(whole) }, modifier = Modifier.padding(top = 16.dp)) { Text("Apply to whole item") }
            return@Column
        }

        var split by remember { mutableStateOf(samples.size / 2) }
        var leftType by remember { mutableStateOf(initialType) }
        var rightType by remember {
            mutableStateOf(if (initialType == SegmentType.Stationary) SegmentType.Moving(TransportMode.WALKING) else SegmentType.Stationary)
        }
        split = split.coerceIn(1, samples.size - 1)
        LaunchedEffect(split) { onReclassifyType(null); onSplitIndexChange(split) }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (split > 1) split-- }) { Icon(Icons.Filled.Remove, contentDescription = "Move split earlier") }
            Slider(
                value = split.toFloat(),
                onValueChange = { split = it.toInt().coerceIn(1, samples.size - 1) },
                valueRange = 1f..(samples.size - 1).toFloat(),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { if (split < samples.size - 1) split++ }) { Icon(Icons.Filled.Add, contentDescription = "Move split later") }
        }

        SideCard("Before", samples.subList(0, split), leftType) { leftType = it }
        SideCard("After", samples.subList(split, samples.size), rightType) { rightType = it }

        Button(onClick = { onSplit(split, leftType, rightType) }, modifier = Modifier.padding(top = 16.dp)) { Text("Split here") }
    }
}

@Composable
private fun SideCard(
    title: String,
    span: List<LocationSampleEntity>,
    type: SegmentType,
    onType: (SegmentType) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (span.isNotEmpty()) {
                val start = span.first().timestampMs
                val end = span.last().timestampMs
                Text(
                    "${Format.time(start)} – ${Format.time(end)} · ${Format.duration(start, end)} · ${span.size} samples",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TypePicker("Type", type, Modifier.padding(top = 8.dp), onType)
        }
    }
}

@Composable
private fun TypePicker(
    label: String,
    selected: SegmentType,
    modifier: Modifier = Modifier,
    onSelect: (SegmentType) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: ${selected.label()}")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TYPE_OPTIONS.forEach { option ->
                DropdownMenuItem(text = { Text(option.label()) }, onClick = { open = false; onSelect(option) })
            }
        }
    }
}
