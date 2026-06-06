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
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.domain.SegmentType

private val TYPE_OPTIONS: List<SegmentType> =
    listOf(SegmentType.Stationary) + TransportMode.MODEL_CLASSES.map { SegmentType.Moving(it) }

private fun SegmentType.label(context: Context): String = when (this) {
    SegmentType.Stationary -> context.getString(R.string.state_stationary)
    is SegmentType.Moving -> context.getString(mode.labelRes)
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.split_edit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }

        val typeLabel = stringResource(R.string.field_type)
        if (samples.size < 2) {
            var whole by remember { mutableStateOf(initialType) }
            LaunchedEffect(whole) { onSplitIndexChange(null); onReclassifyType(whole) }
            Text(
                stringResource(R.string.split_not_enough),
                style = MaterialTheme.typography.bodyMedium
            )
            TypePicker(typeLabel, whole, Modifier.padding(top = 8.dp)) { whole = it }
            Button(onClick = { onConvert(whole) }, modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    stringResource(R.string.action_apply)
                )
            }
            return@Column
        }

        var splitMode by remember { mutableStateOf(true) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                splitMode,
                { splitMode = true },
                { Text(stringResource(R.string.split_tab_split)) })
            FilterChip(
                !splitMode,
                { splitMode = false },
                { Text(stringResource(R.string.split_tab_reclassify)) })
        }

        if (!splitMode) {
            var whole by remember { mutableStateOf(initialType) }
            LaunchedEffect(whole) { onSplitIndexChange(null); onReclassifyType(whole) }
            TypePicker(typeLabel, whole, Modifier.padding(top = 16.dp)) { whole = it }
            Button(onClick = { onConvert(whole) }, modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    stringResource(R.string.action_apply_whole)
                )
            }
            return@Column
        }

        var split by remember { mutableStateOf(samples.size / 2) }
        var leftType by remember { mutableStateOf(initialType) }
        var rightType by remember {
            mutableStateOf(
                if (initialType == SegmentType.Stationary) SegmentType.Moving(
                    TransportMode.WALKING
                ) else SegmentType.Stationary
            )
        }
        split = split.coerceIn(1, samples.size - 1)
        LaunchedEffect(split) { onReclassifyType(null); onSplitIndexChange(split) }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (split > 1) split-- }) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.cd_move_split_earlier)
                )
            }
            Slider(
                value = split.toFloat(),
                onValueChange = { split = it.toInt().coerceIn(1, samples.size - 1) },
                valueRange = 1f..(samples.size - 1).toFloat(),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { if (split < samples.size - 1) split++ }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_move_split_later)
                )
            }
        }

        SideCard(
            stringResource(R.string.split_before),
            samples.subList(0, split),
            leftType
        ) { leftType = it }
        SideCard(
            stringResource(R.string.split_after),
            samples.subList(split, samples.size),
            rightType
        ) { rightType = it }

        Button(
            onClick = { onSplit(split, leftType, rightType) },
            modifier = Modifier.padding(top = 16.dp)
        ) { Text(stringResource(R.string.action_split_here)) }
    }
}

@Composable
private fun SideCard(
    title: String,
    span: List<LocationSampleEntity>,
    type: SegmentType,
    onType: (SegmentType) -> Unit,
) {
    val context = LocalContext.current
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (span.isNotEmpty()) {
                val start = span.first().timestampMs
                val end = span.last().timestampMs
                Text(
                    stringResource(
                        R.string.side_span_summary,
                        Format.time(start),
                        Format.time(end),
                        Format.duration(context, start, end),
                        pluralStringResource(R.plurals.samples_count, span.size, span.size),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TypePicker(
                stringResource(R.string.field_type),
                type,
                Modifier.padding(top = 8.dp),
                onType
            )
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
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text(stringResource(R.string.type_picker, label, selected.label(context)))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TYPE_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label(context)) },
                    onClick = { open = false; onSelect(option) })
            }
        }
    }
}
