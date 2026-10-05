package net.extrawdw.apps.locationhistory.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.data.repo.AppSettings
import net.extrawdw.apps.locationhistory.data.repo.LocationRepository
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.data.places.AppSigningIdentity
import net.extrawdw.apps.locationhistory.data.places.GcloudCommandShell
import net.extrawdw.apps.locationhistory.data.places.MapsApiKeyTestResult
import net.extrawdw.apps.locationhistory.data.places.PlacesGateway
import net.extrawdw.apps.locationhistory.security.MapsApiKeyVault
import net.extrawdw.apps.locationhistory.service.FirebaseTelemetry
import net.extrawdw.apps.locationhistory.service.RecordingController
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val recordingController: RecordingController,
    private val workScheduler: WorkScheduler,
    private val mapsApiKeyVault: MapsApiKeyVault,
    private val placesGateway: PlacesGateway,
    private val signingIdentity: AppSigningIdentity,
    locationRepository: LocationRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            AppSettings(trackingEnabled = false, powerProfile = PowerProfile.BALANCED),
        )

    val sampleCount: StateFlow<Long> = locationRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val mapsApiKeyConfigured: StateFlow<Boolean> = mapsApiKeyVault.configured
    private val _mapsApiKeyTest = MutableStateFlow<MapsApiKeyTestResult?>(null)
    val mapsApiKeyTest = _mapsApiKeyTest.asStateFlow()

    fun gcloudSetupCommand(projectId: String, shell: GcloudCommandShell): String? =
        signingIdentity.gcloudSetupCommand(projectId, shell)

    fun setGoogleCloudProjectId(projectId: String) = viewModelScope.launch {
        settingsRepository.setGoogleCloudProjectId(projectId)
    }

    fun saveMapsApiKey(apiKey: String) = viewModelScope.launch {
        _mapsApiKeyTest.value = null
        runCatching { mapsApiKeyVault.store(apiKey) }
            .onFailure { _mapsApiKeyTest.value = MapsApiKeyTestResult.Failed("Invalid API key") }
    }

    fun removeMapsApiKey() = viewModelScope.launch {
        mapsApiKeyVault.clear()
        _mapsApiKeyTest.value = MapsApiKeyTestResult.NotConfigured
    }

    fun testMapsApiKey() = viewModelScope.launch {
        _mapsApiKeyTest.value = placesGateway.testConfiguredKey()
    }

    fun setAutomaticNearbyDailyLimit(limit: Int) = viewModelScope.launch {
        settingsRepository.setAutomaticNearbyDailyLimit(limit)
    }

    fun setIncludeMapsPlatformInBackup(include: Boolean) = viewModelScope.launch {
        settingsRepository.setIncludeMapsPlatformInBackup(include)
    }

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

    fun setSaveBatteryWhileIdle(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setSaveBatteryWhileIdle(enabled)
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

    /** Master switch for crash & performance telemetry. Applies immediately and persists for next launch. */
    fun setTelemetryEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setTelemetryEnabled(enabled)
        FirebaseTelemetry.apply(enabled)
    }
}
