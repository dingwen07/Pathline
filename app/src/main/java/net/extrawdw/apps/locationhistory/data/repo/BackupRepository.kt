package net.extrawdw.apps.locationhistory.data.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import net.extrawdw.apps.locationhistory.backup.BackupEngine
import net.extrawdw.apps.locationhistory.backup.BackupReporter
import net.extrawdw.apps.locationhistory.backup.BackupReport
import net.extrawdw.apps.locationhistory.backup.RestoreReport
import net.extrawdw.apps.locationhistory.backup.SafBackupStore
import net.extrawdw.apps.locationhistory.backup.SafDir
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.security.BackupCrypto
import net.extrawdw.apps.locationhistory.security.BackupEncryption
import net.extrawdw.apps.locationhistory.security.BackupKeyVault
import net.extrawdw.apps.locationhistory.security.CryptoHeader
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a backup/restore operation, surfaced to the UI. */
sealed interface BackupResult {
    data class Backed(val report: BackupReport) : BackupResult
    data class Restored(val report: RestoreReport) : BackupResult
    data object NoDestination : BackupResult
    data object NeedsReclaim : BackupResult
    data object KeyUnavailable : BackupResult
    data class Error(val message: String) : BackupResult
}

/** What protects a newly-configured backup or a one-time dump. */
sealed interface EncryptionChoice {
    data object None : EncryptionChoice
    data class Password(val password: CharArray) : EncryptionChoice
    /** A PRF secret already obtained from a passkey (the UI runs the ceremony; it needs an Activity). */
    data class Passkey(val secret: ByteArray, val salt: ByteArray, val credentialId: String?) : EncryptionChoice
}

/** Crypto descriptor of an existing backup, so the UI knows how to prompt for restore. */
data class CryptoInfo(val mode: BackupEncryption, val prfSalt: ByteArray?, val credentialId: String?)

/**
 * Application-facing entry point for the SAF backup feature. Owns crypto-material assembly, SAF
 * permission persistence, subdirectory resolution, and the incremental-vs-full decision.
 */
@Singleton
class BackupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val engine: BackupEngine,
    private val safStore: SafBackupStore,
    private val keyVault: BackupKeyVault,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    val config: Flow<BackupConfig> get() = settings.backupConfig

    // --- Destination + encryption configuration ----------------------------------------------

    /** Persist a newly-picked backup tree + optional subdir and write an initial full backup. */
    suspend fun configureDestination(treeUri: Uri, subdir: String?, reporter: BackupReporter = BackupReporter.None): BackupResult {
        persistPermission(treeUri, write = true)
        settings.setBackupTree(treeUri.toString(), subdir)
        return performBackup(full = true, reporter = reporter)
    }

    suspend fun disableEncryption(reporter: BackupReporter = BackupReporter.None): BackupResult {
        keyVault.clear()
        settings.setBackupEncryption(BackupEncryption.NONE, null)
        return performBackup(full = true, reporter = reporter) // rewrite everything as plaintext
    }

    /** Turn on password encryption: mint a DEK, wrap it for the password + cache both locally. */
    suspend fun enablePasswordEncryption(password: CharArray, reporter: BackupReporter = BackupReporter.None): BackupResult {
        val (header, dek) = BackupCrypto.createPasswordHeader(password)
        keyVault.store(dek)
        keyVault.storePassword(password)
        settings.setBackupEncryption(BackupEncryption.PASSWORD, json.encodeToString(CryptoHeader.serializer(), header))
        return performBackup(full = true, reporter = reporter) // re-encrypt under the new DEK
    }

    /** Turn on passkey (PRF) encryption from a secret the UI already obtained via the passkey ceremony. */
    suspend fun enablePasskeyEncryption(choice: EncryptionChoice.Passkey, reporter: BackupReporter = BackupReporter.None): BackupResult {
        val (header, dek) = BackupCrypto.createPasskeyHeader(choice.secret, choice.salt, choice.credentialId)
        keyVault.store(dek)
        keyVault.clearPasswordOnly()
        settings.setBackupEncryption(BackupEncryption.PASSKEY, json.encodeToString(CryptoHeader.serializer(), header))
        return performBackup(full = true, reporter = reporter)
    }

    suspend fun setGpxEnabled(enabled: Boolean) = settings.setGpxEnabled(enabled)
    suspend fun setGpxTree(uri: Uri?) {
        uri?.let { persistPermission(it, write = true) }
        settings.setGpxTree(uri?.toString())
    }

    // --- Running backups ---------------------------------------------------------------------

    suspend fun runScheduledBackup(): BackupResult = performBackup(full = false)
    suspend fun runManagedBackup(reporter: BackupReporter): BackupResult = performBackup(full = false, reporter = reporter)

    private suspend fun performBackup(full: Boolean, reporter: BackupReporter = BackupReporter.None): BackupResult {
        val cfg = settings.backupConfig.first()
        val treeUri = cfg.treeUri ?: return BackupResult.NoDestination
        val tree = safStore.open(Uri.parse(treeUri)) ?: return BackupResult.NeedsReclaim
        val root = rootDir(tree, cfg.subdir)
        val material = material(cfg) ?: return BackupResult.KeyUnavailable
        return try {
            val now = System.currentTimeMillis()
            val needsFull = full || engine.readManifest(root) == null
            val report = if (needsFull) {
                engine.runFull(root, material, now, clearDirtyAfter = true, reporter = reporter)
            } else {
                engine.runIncremental(root, material, now, reporter = reporter)
            }
            if (cfg.gpxEnabled) runCatching {
                exportGpx(cfg, tree, if (needsFull) null else report.sampleWeeksTouched, reporter)
            }.onFailure { AppLog.w(TAG, "gpx export failed: ${it.message}") }
            settings.setLastBackup(now)
            BackupResult.Backed(report)
        } catch (t: Throwable) {
            AppLog.w(TAG, "backup failed: ${t.message}")
            BackupResult.Error(t.message ?: "backup failed")
        }
    }

    private suspend fun exportGpx(cfg: BackupConfig, backupTree: SafDir, weeks: Collection<Long>?, reporter: BackupReporter) {
        val gpxRoot = cfg.gpxTreeUri?.let { safStore.open(Uri.parse(it)) } ?: rootDir(backupTree, cfg.subdir)
        reporter.log("Exporting GPX…")
        val n = engine.exportGpx(gpxRoot, weeks)
        reporter.log("Exported $n GPX file(s)")
    }

    // --- One-time dump + restore -------------------------------------------------------------

    /**
     * Write a complete standalone snapshot to [treeUri]/[subdir] once, with the chosen encryption.
     * Independent of the configured destination, its DEK, and the dirty set.
     */
    suspend fun oneTimeDump(treeUri: Uri, subdir: String?, choice: EncryptionChoice, reporter: BackupReporter): BackupResult {
        persistPermission(treeUri, write = true)
        val tree = safStore.open(treeUri) ?: return BackupResult.NeedsReclaim
        val root = rootDir(tree, subdir)
        val material = when (choice) {
            EncryptionChoice.None -> BackupEngine.Material(BackupCrypto.plaintextHeader(), null)
            is EncryptionChoice.Password -> BackupCrypto.createPasswordHeader(choice.password)
                .let { (h, dek) -> BackupEngine.Material(h, dek) }
            is EncryptionChoice.Passkey -> BackupCrypto.createPasskeyHeader(choice.secret, choice.salt, choice.credentialId)
                .let { (h, dek) -> BackupEngine.Material(h, dek) }
        }
        return try {
            BackupResult.Backed(engine.runFull(root, material, System.currentTimeMillis(), clearDirtyAfter = false, reporter = reporter))
        } catch (t: Throwable) {
            BackupResult.Error(t.message ?: "dump failed")
        }
    }

    /**
     * Restore as-is from the backup at [treeUri] (the folder that directly contains manifest.json).
     * Supply [password] / [prfSecret] matching the backup's encryption; a password backup falls back
     * to the locally-stored password on the same device.
     */
    suspend fun restoreFrom(treeUri: Uri, password: CharArray?, prfSecret: ByteArray?, reporter: BackupReporter): BackupResult {
        persistPermission(treeUri, write = true)
        val tree = safStore.open(treeUri) ?: return BackupResult.Error("cannot read the selected folder")
        val pwd = password ?: keyVault.localPassword()
        return try {
            BackupResult.Restored(engine.restore(tree, pwd, prfSecret, reporter))
        } catch (t: Throwable) {
            AppLog.w(TAG, "restore failed: ${t.message}")
            BackupResult.Error(t.message ?: "restore failed")
        }
    }

    suspend fun hasBackupAt(treeUri: Uri): Boolean {
        val tree = safStore.open(treeUri) ?: return false
        return engine.readManifest(tree) != null
    }

    /** Whether a backup already exists at [treeUri] resolved through [subdir] (for overwrite warnings). */
    suspend fun backupExistsAt(treeUri: Uri, subdir: String?): Boolean {
        val tree = safStore.open(treeUri) ?: return false
        return engine.readManifest(rootDir(tree, subdir)) != null
    }

    /** Crypto descriptor of the backup at [treeUri], or null if none. */
    suspend fun cryptoInfoAt(treeUri: Uri): CryptoInfo? {
        val tree = safStore.open(treeUri) ?: return null
        val crypto = engine.readManifest(tree)?.crypto ?: return null
        return CryptoInfo(crypto.mode, BackupCrypto.prfSaltOf(crypto), crypto.passkey?.credentialId)
    }

    /** Whether the chosen folder name hints it's already a Pathline folder (used to default the subdir box). */
    fun folderLooksLikePathline(treeUri: Uri): Boolean =
        (treeUri.lastPathSegment ?: "").contains("pathline", ignoreCase = true)

    // --- Helpers -----------------------------------------------------------------------------

    private fun rootDir(tree: SafDir, subdir: String?): SafDir =
        subdir?.trim()?.takeIf { it.isNotEmpty() }?.let { tree.childDir(it) } ?: tree

    private fun material(cfg: BackupConfig): BackupEngine.Material? = when (cfg.encryption) {
        BackupEncryption.NONE -> BackupEngine.Material(BackupCrypto.plaintextHeader(), null)
        BackupEncryption.PASSWORD -> {
            val header = parseHeader(cfg) ?: return null
            val dek = keyVault.localDek()
                ?: keyVault.localPassword()?.let { runCatching { BackupCrypto.openDek(header, password = it) }.getOrNull() }
                ?: return null
            BackupEngine.Material(header, dek)
        }
        BackupEncryption.PASSKEY -> {
            val header = parseHeader(cfg) ?: return null
            // The background worker can't run a passkey ceremony — it relies on the cached DEK.
            val dek = keyVault.localDek() ?: return null
            BackupEngine.Material(header, dek)
        }
    }

    private fun parseHeader(cfg: BackupConfig): CryptoHeader? = cfg.cryptoHeaderJson?.let {
        runCatching { json.decodeFromString(CryptoHeader.serializer(), it) }.getOrNull()
    }

    private fun persistPermission(uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { AppLog.w(TAG, "could not persist SAF permission: ${it.message}") }
    }

    private companion object {
        const val TAG = "BackupRepository"
    }
}
