package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sampleCount by viewModel.sampleCount.collectAsStateWithLifecycle()
    val modelStatus = viewModel.modelStatus

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Tracking toggle
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_bg_recording_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.settings_bg_recording_desc),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = settings.trackingEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !permissionsGranted) onRequestPermissions()
                                else viewModel.setTracking(enabled)
                            },
                        )
                    }
                    if (!permissionsGranted) {
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text(stringResource(R.string.settings_grant_permissions)) }
                    }
                }
            }

            // Power profile
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_power_profile), style = MaterialTheme.typography.titleMedium)
                    val profiles = PowerProfile.entries
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        profiles.forEachIndexed { index, profile ->
                            SegmentedButton(
                                selected = settings.powerProfile == profile,
                                onClick = { viewModel.setPowerProfile(profile) },
                                shape = SegmentedButtonDefaults.itemShape(index, profiles.size),
                            ) { Text(stringResource(profile.labelRes())) }
                        }
                    }
                }
            }

            // Stop recording when removed from Recents
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_stop_on_close_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.settings_stop_on_close_desc),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = settings.stopOnTaskRemoved,
                            onCheckedChange = viewModel::setStopOnTaskRemoved,
                        )
                    }
                }
            }

            // Data + model status
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_data_model_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralStringResource(R.plurals.samples_recorded, sampleCount.toInt(), sampleCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.settings_state_model,
                            stringResource(if (modelStatus.stateModelReady) R.string.model_litert else R.string.model_heuristic),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.settings_transport_model,
                            stringResource(if (modelStatus.transportModelReady) R.string.model_litert else R.string.model_heuristic),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = viewModel::trainNow,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.settings_train_model)) }
                    Text(
                        stringResource(R.string.settings_train_desc),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }

            // Backup
            BackupCard()

            // Diagnostics
            var showDiagnostics by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_diagnostics_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_diagnostics_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { showDiagnostics = true },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.settings_open_diagnostics)) }
                }
            }
            if (showDiagnostics) DiagnosticsDialog(onDismiss = { showDiagnostics = false })
        }
    }
}

@androidx.annotation.StringRes
private fun PowerProfile.labelRes(): Int = when (this) {
    PowerProfile.BATTERY_SAVER -> R.string.power_saver
    PowerProfile.BALANCED -> R.string.power_balanced
    PowerProfile.HIGH_ACCURACY -> R.string.power_accurate
}
