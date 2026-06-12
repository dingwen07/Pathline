package net.extrawdw.apps.locationhistory.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.data.repo.AppSettings
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.service.RecordingController
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val recordingController: RecordingController,
    private val workScheduler: WorkScheduler,
    locationRepository: LocationRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            AppSettings(trackingEnabled = false, powerProfile = PowerProfile.BALANCED),
        )

    val sampleCount: StateFlow<Long> = locationRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Toggle background recording. Assumes the required permissions were already granted. */
    fun setTracking(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setTrackingEnabled(enabled)
        if (enabled) {
            recordingController.startTracking()
            workScheduler.schedulePeriodicTimelineMaintenance()
            workScheduler.schedulePeriodicBackup()
        } else {
            recordingController.disableTracking()
        }
    }

    fun setPowerProfile(profile: PowerProfile) = viewModelScope.launch {
        settingsRepository.setPowerProfile(profile)
    }

    /** Toggle whether removing the app from Recents stops recording. */
    fun setStopOnTaskRemoved(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setStopOnTaskRemoved(enabled)
    }

    /**
     * The on/off switch for the third-party data API
     * Turning it off also clears the "don't ask again" flag
     */
    fun setApiAccessEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setApiAccessEnabled(enabled)
        if (!enabled) settingsRepository.setApiAccessConsentNeverAsk(false)
    }
}
