package net.extrawdw.apps.locationhistory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.service.RecordingController
import net.extrawdw.apps.locationhistory.ui.OnboardingScreen
import net.extrawdw.apps.locationhistory.ui.OnboardingViewModel
import net.extrawdw.apps.locationhistory.ui.PlacesScreen
import net.extrawdw.apps.locationhistory.ui.SettingsScreen
import net.extrawdw.apps.locationhistory.ui.TimelineScreen
import net.extrawdw.apps.locationhistory.ui.rememberPathlinePermissions
import net.extrawdw.apps.locationhistory.ui.theme.PathlineTheme
import net.extrawdw.apps.locationhistory.work.WorkScheduler

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject lateinit var recordingController: RecordingController
    @javax.inject.Inject lateinit var workScheduler: WorkScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Self-heal: if tracking was previously enabled, make sure the heartbeat is armed.
        lifecycleScope.launch {
            recordingController.resumeIfPreviouslyEnabled()
            workScheduler.schedulePeriodicTimelineMaintenance()
            workScheduler.enqueueTimelineMaintenanceNow(TimeBuckets.dayEpoch(System.currentTimeMillis()), "app_open")
        }
        setContent {
            PathlineTheme {
                PathlineRoot()
            }
        }
    }
}

@Composable
fun PathlineRoot(onboardingViewModel: OnboardingViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    var destination by rememberSaveable { mutableStateOf(AppDestinations.TIMELINE) }
    val permissions = rememberPathlinePermissions()
    val onboardingComplete by onboardingViewModel.onboardingComplete.collectAsStateWithLifecycle()

    // Gate the app behind first-run onboarding. `null` = still loading the flag (avoid flashing).
    when (onboardingComplete) {
        null -> {
            Surface(Modifier.fillMaxSize()) {}
            return
        }
        false -> {
            OnboardingScreen(
                permissions = permissions,
                onFinish = { startTracking -> onboardingViewModel.finish(startTracking) },
            )
            return
        }
        else -> Unit
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach { dest ->
                item(
                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                    label = { Text(dest.label) },
                    selected = dest == destination,
                    onClick = { destination = dest },
                )
            }
        },
    ) {
        // Keep Timeline (and its GoogleMap/MapView) permanently composed so switching tabs doesn't
        // destroy and recreate the expensive map; Places/Settings draw opaquely on top when active.
        Box(Modifier.fillMaxSize()) {
            TimelineScreen()
            when (destination) {
                AppDestinations.TIMELINE -> Unit
                AppDestinations.PLACES -> Surface(Modifier.fillMaxSize()) { PlacesScreen() }
                AppDestinations.SETTINGS -> Surface(Modifier.fillMaxSize()) {
                    SettingsScreen(
                        permissionsGranted = permissions.granted,
                        onRequestPermissions = permissions::request,
                    )
                }
            }
        }
    }
}

enum class AppDestinations(val label: String, val icon: ImageVector) {
    TIMELINE("Timeline", Icons.AutoMirrored.Filled.List),
    PLACES("Places", Icons.Filled.Place),
    SETTINGS("Settings", Icons.Filled.Settings),
}
