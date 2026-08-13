package net.extrawdw.apps.locationhistory.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.data.places.GcloudCommandShell
import net.extrawdw.apps.locationhistory.data.places.MapsApiKeyTestResult
import net.extrawdw.apps.locationhistory.data.repo.AppSettings

/** User-facing setup for Google-backed place search and travel times. */
@Composable
internal fun MapsPlatformSettingsCard(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val configured by viewModel.mapsApiKeyConfigured.collectAsStateWithLifecycle()
    val testResult by viewModel.mapsApiKeyTest.collectAsStateWithLifecycle()
    // API keys must never enter the Activity's saved-state Bundle. Keep this transient field only
    // in composition memory; the vault becomes the sole persisted copy after Save is pressed.
    var keyInput by remember { mutableStateOf("") }
    var projectInput by rememberSaveable(settings.googleCloudProjectId) {
        mutableStateOf(settings.googleCloudProjectId)
    }
    var commandSheetVisible by rememberSaveable { mutableStateOf(false) }
    var commandShell by rememberSaveable { mutableStateOf(GcloudCommandShell.POSIX) }
    val command = remember(projectInput, commandShell) {
        viewModel.gcloudSetupCommand(projectInput, commandShell)
    }

    val clipboardLabel = stringResource(R.string.maps_command_clipboard_label)
    val shareTitle = stringResource(R.string.maps_share_command_chooser)

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.maps_services_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.maps_services_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = {
                    Text(
                        stringResource(
                            if (configured) R.string.maps_api_key_new else R.string.maps_api_key
                        )
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.saveMapsApiKey(keyInput)
                        keyInput = ""
                    },
                    enabled = keyInput.isNotBlank(),
                ) {
                    Text(
                        stringResource(
                            if (configured) R.string.maps_api_key_update else R.string.action_save
                        )
                    )
                }
                OutlinedButton(onClick = viewModel::testMapsApiKey, enabled = configured) {
                    Text(stringResource(R.string.maps_api_key_test))
                }
                if (configured) {
                    OutlinedButton(onClick = viewModel::removeMapsApiKey) {
                        Text(stringResource(R.string.maps_api_key_remove))
                    }
                }
            }
            Text(
                when (testResult) {
                    MapsApiKeyTestResult.Working ->
                        stringResource(R.string.maps_api_key_working)
                    MapsApiKeyTestResult.NotConfigured ->
                        stringResource(R.string.maps_api_key_missing)
                    is MapsApiKeyTestResult.Failed ->
                        stringResource(R.string.maps_api_key_test_failed)
                    null -> stringResource(
                        if (configured) R.string.maps_api_key_saved else R.string.maps_api_key_prompt
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                stringResource(R.string.maps_auto_suggestions_title),
                style = MaterialTheme.typography.labelLarge,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(
                    0 to stringResource(R.string.maps_auto_suggestions_off),
                    30 to stringResource(R.string.maps_auto_suggestions_daily),
                    -1 to stringResource(R.string.maps_auto_suggestions_unlimited),
                )
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = settings.automaticNearbyDailyLimit == value,
                        onClick = { viewModel.setAutomaticNearbyDailyLimit(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(label) }
                }
            }
            Text(
                stringResource(R.string.maps_auto_suggestions_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.maps_backup_key_title))
                    Text(
                        stringResource(R.string.maps_backup_key_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.includeMapsPlatformInBackup,
                    onCheckedChange = viewModel::setIncludeMapsPlatformInBackup,
                )
            }

            Text(
                stringResource(R.string.maps_create_key_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                stringResource(R.string.maps_create_key_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = projectInput,
                onValueChange = {
                    projectInput = it
                    commandSheetVisible = false
                    viewModel.setGoogleCloudProjectId(it)
                },
                label = { Text(stringResource(R.string.maps_cloud_project_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (command != null) {
                GcloudCommandShellSelector(
                    selected = commandShell,
                    onSelected = { commandShell = it },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            commandSheetVisible = true
                        }
                    ) {
                        Text(stringResource(R.string.maps_view_command))
                    }
                    OutlinedButton(
                        onClick = { copyText(context, command, clipboardLabel) }
                    ) {
                        Text(stringResource(R.string.maps_copy_command))
                    }
                    OutlinedButton(
                        onClick = { shareText(context, command, shareTitle) }
                    ) {
                        Text(stringResource(R.string.maps_share_command))
                    }
                }
                Text(
                    stringResource(R.string.maps_command_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (projectInput.isNotBlank()) {
                Text(
                    stringResource(R.string.maps_cloud_project_id_invalid),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (commandSheetVisible && command != null) {
        MapsSetupCommandSheet(
            command = command,
            commandShell = commandShell,
            onCommandShellSelected = { commandShell = it },
            onDismiss = { commandSheetVisible = false },
            onCopy = { copyText(context, command, clipboardLabel) },
            onShare = { shareText(context, command, shareTitle) },
        )
    }
}

@Composable
private fun GcloudCommandShellSelector(
    selected: GcloudCommandShell,
    onSelected: (GcloudCommandShell) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val options = listOf(
            GcloudCommandShell.POSIX to stringResource(R.string.maps_command_shell),
            GcloudCommandShell.POWERSHELL to
                stringResource(R.string.maps_command_powershell),
        )
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelected(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapsSetupCommandSheet(
    command: String,
    commandShell: GcloudCommandShell,
    onCommandShellSelected: (GcloudCommandShell) -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.maps_setup_command_title),
                style = MaterialTheme.typography.titleLarge,
            )
            GcloudCommandShellSelector(
                selected = commandShell,
                onSelected = onCommandShellSelected,
            )
            SelectionContainer {
                Text(
                    command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onCopy) {
                    Text(stringResource(R.string.maps_copy_command))
                }
                OutlinedButton(onClick = onShare) {
                    Text(stringResource(R.string.maps_share_command))
                }
            }
            Text(
                stringResource(R.string.maps_command_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun copyText(context: Context, text: String, label: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareText(context: Context, text: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}
