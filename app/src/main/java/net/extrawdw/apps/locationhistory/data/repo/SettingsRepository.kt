package net.extrawdw.apps.locationhistory.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Power profile: trades accuracy against battery for the active recording cadence. */
enum class PowerProfile { BATTERY_SAVER, BALANCED, HIGH_ACCURACY }

data class AppSettings(
    val trackingEnabled: Boolean,
    val powerProfile: PowerProfile,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyTracking = booleanPreferencesKey("tracking_enabled")
    private val keyProfile = stringPreferencesKey("power_profile")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            trackingEnabled = prefs[keyTracking] ?: false,
            powerProfile = prefs[keyProfile]?.let {
                runCatching { PowerProfile.valueOf(it) }.getOrNull()
            } ?: PowerProfile.BALANCED,
        )
    }

    suspend fun setTrackingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyTracking] = enabled }
    }

    suspend fun setPowerProfile(profile: PowerProfile) {
        context.dataStore.edit { it[keyProfile] = profile.name }
    }
}
