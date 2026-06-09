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
import kotlinx.coroutines.flow.first
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
    /** When true, removing the app from Recents stops recording and turns tracking off. */
    val stopOnTaskRemoved: Boolean = true,
    /**
     * The single on/off switch for the third-party data API. When false the
     * [net.extrawdw.apps.locationhistory.api.PathlineProvider] denies every data read regardless of
     * per-app permissions. Defaults off — the user opts in via the first-run consent screen or the
     * Settings toggle.
     */
    val apiAccessEnabled: Boolean = false,
    /**
     * When true the first-run API-access consent screen is suppressed: entering "Access to Pathline
     * data" goes straight to the access manager. Set by the consent screen's "don't ask again" choice.
     */
    val apiAccessConsentNeverAsk: Boolean = false,
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
    val lastBackupMs: Long,
)

/**
 * Automatic open-format GPX export, configured independently of (and not requiring) the encrypted
 * backup. [treeUri] is the dedicated SAF location GPX week files are written to; null = GPX
 * auto-export is off. [lastExportMs] floors the next incremental run's "changed since" query.
 */
data class GpxConfig(
    val treeUri: String?,
    val lastExportMs: Long,
)

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val keyTracking = booleanPreferencesKey("tracking_enabled")
    private val keyProfile = stringPreferencesKey("power_profile")
    private val keyOnboarded = booleanPreferencesKey("onboarding_complete")
    private val keyStopOnTaskRemoved = booleanPreferencesKey("stop_on_task_removed")
    private val keyAutostartSuppressed = booleanPreferencesKey("autostart_suppressed")
    private val keyApiEnabled = booleanPreferencesKey("api_access_enabled")
    private val keyApiNeverAsk = booleanPreferencesKey("api_access_consent_never_ask")

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
            stopOnTaskRemoved = prefs[keyStopOnTaskRemoved] ?: true,
            apiAccessEnabled = prefs[keyApiEnabled] ?: false,
            apiAccessConsentNeverAsk = prefs[keyApiNeverAsk] ?: false,
        )
    }

    /** Current API-access switch state, read once. Used by the provider on the (synchronous) IPC path. */
    suspend fun apiAccessEnabled(): Boolean =
        context.dataStore.data.map { it[keyApiEnabled] ?: false }.first()

    suspend fun setApiAccessEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyApiEnabled] = enabled }
    }

    /** Suppress the first-run API-access consent screen (the "don't ask again" choice). */
    suspend fun setApiAccessConsentNeverAsk(neverAsk: Boolean) {
        context.dataStore.edit { it[keyApiNeverAsk] = neverAsk }
    }

    suspend fun setTrackingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyTracking] = enabled }
    }

    suspend fun setStopOnTaskRemoved(enabled: Boolean) {
        context.dataStore.edit { it[keyStopOnTaskRemoved] = enabled }
    }

    /**
     * Hidden (non-user-facing) flag set when the app is removed from Recents: it suppresses the
     * passive autostart of the recording service until the app is next launched into the foreground.
     * Persisted so it survives the process death that follows task removal — an in-memory flag would
     * reset on the next AR/geofence-triggered process restart and let recording silently resume.
     */
    val autostartSuppressed: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyAutostartSuppressed] ?: false
    }

    suspend fun setAutostartSuppressed(suppressed: Boolean) {
        context.dataStore.edit { it[keyAutostartSuppressed] = suppressed }
    }

    suspend fun setPowerProfile(profile: PowerProfile) {
        context.dataStore.edit { it[keyProfile] = profile.name }
    }

    // --- Backup configuration -----------------------------------------------------------------

    private val keyBackupTree = stringPreferencesKey("backup_tree_uri")
    private val keyBackupSubdir = stringPreferencesKey("backup_subdir")
    private val keyBackupEncryption = stringPreferencesKey("backup_encryption")
    private val keyBackupHeader = stringPreferencesKey("backup_crypto_header")
    private val keyGpxTree = stringPreferencesKey("backup_gpx_tree_uri")
    private val keyGpxLastExport = longPreferencesKey("gpx_last_export_ms")
    private val keyLastBackup = longPreferencesKey("backup_last_ms")

    val backupConfig: Flow<BackupConfig> = context.dataStore.data.map { prefs ->
        BackupConfig(
            treeUri = prefs[keyBackupTree],
            subdir = prefs[keyBackupSubdir],
            encryption = prefs[keyBackupEncryption]?.let {
                runCatching { BackupEncryption.valueOf(it) }.getOrNull()
            } ?: BackupEncryption.NONE,
            cryptoHeaderJson = prefs[keyBackupHeader],
            lastBackupMs = prefs[keyLastBackup] ?: 0L,
        )
    }

    val gpxConfig: Flow<GpxConfig> = context.dataStore.data.map { prefs ->
        GpxConfig(
            treeUri = prefs[keyGpxTree],
            lastExportMs = prefs[keyGpxLastExport] ?: 0L,
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
            if (cryptoHeaderJson == null) it.remove(keyBackupHeader) else it[keyBackupHeader] =
                cryptoHeaderJson
        }
    }

    /**
     * Set (or clear, with null) the GPX auto-export destination. Always resets the last-export
     * watermark so a freshly-chosen folder receives a full export and stale state never carries over.
     */
    suspend fun setGpxTree(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(keyGpxTree) else it[keyGpxTree] = uri
            it.remove(keyGpxLastExport)
        }
    }

    suspend fun setGpxLastExport(ms: Long) {
        context.dataStore.edit { it[keyGpxLastExport] = ms }
    }

    suspend fun setLastBackup(ms: Long) {
        context.dataStore.edit { it[keyLastBackup] = ms }
    }
}
