package net.extrawdw.apps.locationhistory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.service.RecordingController
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val recordingController: RecordingController,
    private val workScheduler: WorkScheduler,
) : ViewModel() {

    /** null while loading; true once onboarding has been completed or skipped. */
    val onboardingComplete: StateFlow<Boolean?> = settingsRepository.onboardingComplete
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Mark onboarding done. When the recording permissions were granted, turn tracking on so the
     * timeline starts populating right away.
     */
    fun finish(startTracking: Boolean) = viewModelScope.launch {
        if (startTracking) {
            settingsRepository.setTrackingEnabled(true)
            recordingController.startTracking()
            workScheduler.schedulePeriodicTimelineMaintenance()
            workScheduler.schedulePeriodicExport()
        }
        settingsRepository.setOnboardingComplete(true)
    }
}

private enum class OnboardingStep { WELCOME, PERMISSIONS, BACKGROUND }

/**
 * First-run onboarding. Introduces the app, then walks the user through granting foreground
 * permissions (location, activity recognition, notifications) and finally "Allow all the time"
 * background location, which Android requires as a separate step. Each permission step advances
 * automatically once granted; the user can skip the optional background step.
 */
@Composable
fun OnboardingScreen(
    permissions: PathlinePermissions,
    onFinish: (startTracking: Boolean) -> Unit,
) {
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }

    // Advance automatically as each permission phase is granted.
    LaunchedEffect(permissions.granted) {
        if (step == OnboardingStep.PERMISSIONS && permissions.granted) step = OnboardingStep.BACKGROUND
    }
    LaunchedEffect(permissions.backgroundGranted) {
        if (step == OnboardingStep.BACKGROUND && permissions.backgroundGranted) onFinish(permissions.granted)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            StepDots(step.ordinal, OnboardingStep.entries.size, Modifier.padding(bottom = 8.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (step) {
                    OnboardingStep.WELCOME -> WelcomeContent()
                    OnboardingStep.PERMISSIONS -> PermissionContent(
                        icon = Icons.Filled.LocationOn,
                        title = "A few permissions",
                        body = "Pathline records where you go and how you get there. It needs a few permissions to work correctly.",
                        rows = listOf(
                            PermissionRow(
                                Icons.Filled.Notifications,
                                "Notifications",
                                "Shows a status notice while recording is active.",
                            ),
                            PermissionRow(
                                Icons.Filled.LocationOn,
                                "Location",
                                "Records where you go so Pathline can build your timeline.",
                            ),
                            PermissionRow(
                                Icons.AutoMirrored.Filled.DirectionsRun,
                                "Physical activity",
                                "Detects when you're moving and how you travel, so recording stays light on the battery.",
                            ),
                            PermissionRow(
                                Icons.Filled.Phone,
                                "Phone (optional)",
                                "Reads cell-signal strength to improve location context. Pathline works fine if you deny this.",
                            ),
                        ),
                    )
                    OnboardingStep.BACKGROUND -> PermissionContent(
                        icon = Icons.Filled.LocationOn,
                        title = "Keep recording in the background",
                        body = "To build a complete timeline, location access needs to be set to " +
                            "\"Allow all the time\" so Pathline keeps recording when the app is closed. " +
                            "Android asks for this separately on the next screen.",
                        rows = emptyList(),
                    )
                }
            }

            // Primary / secondary actions per step.
            when (step) {
                OnboardingStep.WELCOME -> PrimaryButton("Get started") { step = OnboardingStep.PERMISSIONS }
                OnboardingStep.PERMISSIONS -> {
                    PrimaryButton(if (permissions.granted) "Continue" else "Allow access") {
                        if (permissions.granted) step = OnboardingStep.BACKGROUND
                        else permissions.requestForeground()
                    }
                    TextButton(
                        onClick = { step = OnboardingStep.BACKGROUND },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Not now") }
                }
                OnboardingStep.BACKGROUND -> {
                    PrimaryButton(if (permissions.backgroundGranted) "Finish" else "Allow all the time") {
                        if (permissions.backgroundGranted) onFinish(permissions.granted)
                        else permissions.requestBackground()
                    }
                    TextButton(
                        onClick = { onFinish(permissions.granted) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Maybe later") }
                }
            }
        }
    }
}

@Composable
private fun WelcomeContent() {
    Icon(
        Icons.Filled.PlayArrow,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.size(20.dp))
    Text(
        "Welcome to Pathline",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.size(12.dp))
    Text(
        "Pathline keeps a private, on-device timeline of the places you visit and the trips between " +
            "them — no account, and your location never leaves your phone.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private data class PermissionRow(val icon: ImageVector, val title: String, val description: String)

@Composable
private fun PermissionContent(
    icon: ImageVector,
    title: String,
    body: String,
    rows: List<PermissionRow>,
) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.size(20.dp))
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.size(12.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (rows.isNotEmpty()) {
        Spacer(Modifier.size(24.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            rows.forEach { row ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        row.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            row.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label)
    }
}

@Composable
private fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(total) { index ->
            val active = index <= current
            Surface(
                shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (index == current) 10.dp else 8.dp),
                content = {},
            )
        }
    }
}
