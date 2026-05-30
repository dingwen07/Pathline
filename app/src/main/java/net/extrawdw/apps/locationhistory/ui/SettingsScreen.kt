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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
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
                            Text("Background recording", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Uses motion sensors and geofencing so GPS only runs while you're moving — easy on the battery.",
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
                        ) { Text("Grant location & activity permissions") }
                    }
                }
            }

            // Power profile
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Power profile", style = MaterialTheme.typography.titleMedium)
                    val profiles = PowerProfile.entries
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        profiles.forEachIndexed { index, profile ->
                            SegmentedButton(
                                selected = settings.powerProfile == profile,
                                onClick = { viewModel.setPowerProfile(profile) },
                                shape = SegmentedButtonDefaults.itemShape(index, profiles.size),
                            ) { Text(profile.label()) }
                        }
                    }
                }
            }

            // Data + model status
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Data & model", style = MaterialTheme.typography.titleMedium)
                    Text("$sampleCount location samples recorded", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "State model: ${if (modelStatus.stateModelReady) "LiteRT" else "heuristic fallback"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Transport model: ${if (modelStatus.transportModelReady) "LiteRT" else "heuristic fallback"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = viewModel::trainNow,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Train model") }
                    Text(
                        "Training runs only while charging to save battery.",
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
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Inspect and share internal data and session logs.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { showDiagnostics = true },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Open diagnostics") }
                }
            }
            if (showDiagnostics) DiagnosticsDialog(onDismiss = { showDiagnostics = false })
        }
    }
}

private fun PowerProfile.label(): String = when (this) {
    PowerProfile.BATTERY_SAVER -> "Saver"
    PowerProfile.BALANCED -> "Balanced"
    PowerProfile.HIGH_ACCURACY -> "Accurate"
}
