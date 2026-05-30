package net.extrawdw.apps.locationhistory.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.extrawdw.apps.locationhistory.security.BackupEncryption
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Power profile: trades accuracy against battery for the active recording cadence. */
enum class PowerProfile { BATTERY_SAVER, BALANCED, HIGH_ACCURACY }

data class AppSettings(
    val trackingEnabled: Boolean,
    val powerProfile: PowerProfile,
)

/**
 * Where and how backups are written. [treeUri] is a persisted SAF tree (Drive / local / etc.);
 * when null no automatic backup destination is configured. [cryptoHeaderJson] is the serialized
 * crypto header (salt + wrapped DEK) reused across incremental runs so partitions share one DEK.
 */
data class BackupConfig(
    val treeUri: String?,
    /** Optional subdirectory under [treeUri] that holds the backup; null/blank = the tree root. */
    val subdir: String?,
    val encryption: BackupEncryption,
    val cryptoHeaderJson: String?,
    val gpxEnabled: Boolean,
    val gpxTreeUri: String?,
    val lastBackupMs: Long,
)

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val keyTracking = booleanPreferencesKey("tracking_enabled")
    private val keyProfile = stringPreferencesKey("power_profile")
    private val keyOnboarded = booleanPreferencesKey("onboarding_complete")

    /** Whether the first-run onboarding flow has been completed or skipped. */
    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyOnboarded] ?: false
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { it[keyOnboarded] = complete }
    }

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

    // --- Backup configuration -----------------------------------------------------------------

    private val keyBackupTree = stringPreferencesKey("backup_tree_uri")
    private val keyBackupSubdir = stringPreferencesKey("backup_subdir")
    private val keyBackupEncryption = stringPreferencesKey("backup_encryption")
    private val keyBackupHeader = stringPreferencesKey("backup_crypto_header")
    private val keyGpxEnabled = booleanPreferencesKey("backup_gpx_enabled")
    private val keyGpxTree = stringPreferencesKey("backup_gpx_tree_uri")
    private val keyLastBackup = longPreferencesKey("backup_last_ms")

    val backupConfig: Flow<BackupConfig> = context.dataStore.data.map { prefs ->
        BackupConfig(
            treeUri = prefs[keyBackupTree],
            subdir = prefs[keyBackupSubdir],
            encryption = prefs[keyBackupEncryption]?.let {
                runCatching { BackupEncryption.valueOf(it) }.getOrNull()
            } ?: BackupEncryption.NONE,
            cryptoHeaderJson = prefs[keyBackupHeader],
            gpxEnabled = prefs[keyGpxEnabled] ?: false,
            gpxTreeUri = prefs[keyGpxTree],
            lastBackupMs = prefs[keyLastBackup] ?: 0L,
        )
    }

    suspend fun setBackupTree(uri: String?, subdir: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(keyBackupTree) else it[keyBackupTree] = uri
            val sub = subdir?.trim()
            if (sub.isNullOrEmpty()) it.remove(keyBackupSubdir) else it[keyBackupSubdir] = sub
        }
    }

    /** Persist encryption mode and (for an encrypted backup) its crypto header. */
    suspend fun setBackupEncryption(mode: BackupEncryption, cryptoHeaderJson: String?) {
        context.dataStore.edit {
            it[keyBackupEncryption] = mode.name
            if (cryptoHeaderJson == null) it.remove(keyBackupHeader) else it[keyBackupHeader] = cryptoHeaderJson
        }
    }

    suspend fun setGpxEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyGpxEnabled] = enabled }
    }

    suspend fun setGpxTree(uri: String?) {
        context.dataStore.edit { if (uri == null) it.remove(keyGpxTree) else it[keyGpxTree] = uri }
    }

    suspend fun setLastBackup(ms: Long) {
        context.dataStore.edit { it[keyLastBackup] = ms }
    }
}
