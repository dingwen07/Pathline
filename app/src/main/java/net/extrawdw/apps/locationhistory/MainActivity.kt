package net.extrawdw.apps.locationhistory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.service.RecordingController
import net.extrawdw.apps.locationhistory.ui.OnboardingScreen
import net.extrawdw.apps.locationhistory.ui.OnboardingViewModel
import net.extrawdw.apps.locationhistory.ui.MapExplorerScreen
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
            workScheduler.schedulePeriodicBackup()
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
                viewModel = onboardingViewModel,
            )
            return
        }
        else -> Unit
    }

    // The Map tab forces the whole app (nav pane + map) into Dark Mode while it is active.
    PathlineTheme(darkTheme = if (destination == AppDestinations.MAP) true else isSystemInDarkTheme()) {
        // Resolve labels here in the composable scope; the navigationSuiteItems builder below is not
        // a @Composable context, so stringResource() can't be called inside it.
        val destinationLabels = AppDestinations.entries.map { stringResource(it.labelRes) }
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                AppDestinations.entries.forEachIndexed { index, dest ->
                    val label = destinationLabels[index]
                    item(
                        icon = { Icon(dest.icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = dest == destination,
                        onClick = { destination = dest },
                    )
                }
            },
        ) {
            // Both map screens (Timeline and Explorer) keep their GoogleMap/MapView permanently
            // composed so switching tabs doesn't destroy and recreate the expensive map — that
            // recreation is what flashes a blank/white map on re-entry. zIndex puts the active
            // screen on top; the inactive map stays warm underneath. Explorer is only mounted
            // after its first visit so it costs nothing until used. Places/Settings are cheap and
            // drawn opaquely above everything when active.
            var mapOpened by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(destination) { if (destination == AppDestinations.MAP) mapOpened = true }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().zIndex(if (destination == AppDestinations.TIMELINE) 1f else 0f)) {
                    TimelineScreen()
                }
                if (mapOpened) {
                    Box(Modifier.fillMaxSize().zIndex(if (destination == AppDestinations.MAP) 1f else 0f)) {
                        MapExplorerScreen()
                    }
                }
                when (destination) {
                    AppDestinations.PLACES -> Surface(Modifier.fillMaxSize().zIndex(2f)) { PlacesScreen() }
                    AppDestinations.SETTINGS -> Surface(Modifier.fillMaxSize().zIndex(2f)) {
                        SettingsScreen(
                            permissionsGranted = permissions.granted,
                            onRequestPermissions = permissions::request,
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

enum class AppDestinations(@param:StringRes val labelRes: Int, val icon: ImageVector) {
    TIMELINE(R.string.nav_timeline, Icons.AutoMirrored.Filled.List),
    PLACES(R.string.nav_places, Icons.Filled.Place),
    MAP(R.string.nav_explorer, Icons.Filled.Map),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}
