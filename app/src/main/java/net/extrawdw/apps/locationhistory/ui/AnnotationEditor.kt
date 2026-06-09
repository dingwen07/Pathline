package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.domain.AnnotationData
import net.extrawdw.apps.locationhistory.domain.TagCanonicalizer

/**
 * Hoisted state for the annotation editor. Holds the user's in-progress note + tag edits and the
 * read-only memory map. Tags are de-duplicated by canonical name on entry so the chip row never shows
 * two spellings of the same tag. Editing is local; persistence is the caller's job (see [saveEdits]
 * callers) so it can run on a long-lived scope that outlives the editor's composition.
 */
class AnnotationEditState {
    /** False until the initial load resolves; gates the Save action so we never write a blank over real data. */
    var loaded by mutableStateOf(false)
        private set
    var note by mutableStateOf("")
    val tags = mutableStateListOf<String>()
    var memories by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun apply(data: AnnotationData) {
        note = data.note
        tags.clear(); tags.addAll(data.tags)
        memories = data.memories
        loaded = true
    }

    /** Add [display] unless it's empty or a different spelling of a tag already present. */
    fun addTag(display: String) {
        val trimmed = display.trim()
        val canonical = TagCanonicalizer.canonicalize(trimmed)
        if (canonical.isEmpty()) return
        if (tags.any { TagCanonicalizer.canonicalize(it) == canonical }) return
        tags.add(trimmed)
    }

    fun removeTag(display: String) {
        tags.remove(display)
    }
}

/** Remember an [AnnotationEditState] for [target]/[id] and (re)load it whenever the target changes. */
@Composable
fun rememberAnnotationEditState(
    target: AnnotationTarget,
    id: Long,
    load: suspend (AnnotationTarget, Long) -> AnnotationData,
): AnnotationEditState {
    val state = remember(target, id) { AnnotationEditState() }
    LaunchedEffect(target, id) { state.apply(load(target, id)) }
    return state
}

/**
 * The editable annotation surface: a free-text **note**, a **tags** chip row with an add field, and a
 * read-only **memories** KV list (shown only when present). Reused by the visit/trip dialog and folded
 * into the place editor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnnotationEditorBody(state: AnnotationEditState, modifier: Modifier = Modifier) {
    var tagInput by remember { mutableStateOf("") }
    fun commitTag() {
        state.addTag(tagInput)
        tagInput = ""
    }

    Column(modifier) {
        SectionLabel(stringResource(R.string.annotations_notes_label))
        OutlinedTextField(
            value = state.note,
            onValueChange = { state.note = it },
            placeholder = { Text(stringResource(R.string.annotations_notes_placeholder)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            minLines = 3,
        )

        SectionLabel(
            stringResource(R.string.annotations_tags_label),
            modifier = Modifier.padding(top = 16.dp),
        )
        if (state.tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { state.removeTag(tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.cd_remove_tag),
                            )
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = tagInput,
            onValueChange = { tagInput = it },
            label = { Text(stringResource(R.string.annotations_add_tag)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitTag() }),
            trailingIcon = {
                IconButton(onClick = { commitTag() }, enabled = tagInput.isNotBlank()) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.annotations_add_tag),
                    )
                }
            },
        )

        if (state.memories.isNotEmpty()) {
            SectionLabel(
                stringResource(R.string.annotations_memories_label),
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                stringResource(R.string.annotations_memories_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.memories.forEach { (key, value) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        key,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/**
 * Full-screen editor for a confirmed **visit** or **trip**'s notes + tags (memories shown read-only).
 * A brief header ([briefTitle] + [briefSubtitle], built timeline-style by the caller) identifies which
 * item is being annotated. Save reconciles the edits via [onSave]; Close/back discards. Persistence is
 * handed to [onSave] (a screen ViewModel) so it completes even after this dialog leaves composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationEditDialog(
    target: AnnotationTarget,
    id: Long,
    title: String,
    briefTitle: String,
    briefSubtitle: String,
    load: suspend (AnnotationTarget, Long) -> AnnotationData,
    onSave: (AnnotationTarget, Long, String, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberAnnotationEditState(target, id, load)
    FullScreenDialog(onDismiss = onDismiss) { requestClose ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { requestClose(onDismiss) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            enabled = state.loaded,
                            onClick = {
                                requestClose { onSave(target, id, state.note, state.tags.toList()) }
                            },
                        ) { Text(stringResource(R.string.action_save)) }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                AnnotationBriefHeader(briefTitle, briefSubtitle)
                AnnotationEditorBody(state)
            }
        }
    }
}

/** A compact "which item is this?" header — the annotated visit/trip's name + timeline-style summary. */
@Composable
private fun AnnotationBriefHeader(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
    }
}
