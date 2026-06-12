package net.extrawdw.apps.locationhistory.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import net.extrawdw.apps.locationhistory.core.AppLog
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the SQLCipher passphrase for the local database using the standard *wrapped-key* pattern:
 *
 *  1. A random 256-bit database passphrase is generated once.
 *  2. A non-exportable, hardware-backed AES key in the Android Keystore wraps that passphrase
 *     (see [KeystoreWrap]).
 *  3. The wrapped blob is stored in a small file in the app's private no-backup dir; the raw
 *     passphrase is never persisted in the clear.
 *
 * **The Keystore key is intentionally NOT gated on user authentication.** The 24/7 background
 * recorder must open the database while the screen is off and the device is locked, so requiring a
 * biometric/lock-screen unlock would break recording. The encryption therefore protects against
 * *offline* extraction (a powered-off device imaged forensically, or a stray cloud backup that
 * accidentally included the DB), not against an attacker with the running, unlocked device.
 */
@Singleton
class DatabaseKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val lock = Any()

    /** AES-GCM wrapped-passphrase blob. */
    private val wrappedFile: File get() = File(context.noBackupFilesDir, WRAPPED_FILE)

    /**
     * The 32-byte database passphrase. Generates + wraps a new one on first use. A fresh passphrase
     * is generated ONLY when the stored one is provably unrecoverable: the wrapped file is
     * missing/empty, the GCM tag check fails (corrupt blob, or the Keystore key was invalidated —
     * e.g. by a lock-credential reset — so a different key now decrypts it), or the Keystore entry
     * itself is unrecoverable. Any other unwrap failure (Keystore daemon hiccup, post-OTA race)
     * propagates: a crash this launch is recoverable on the next one, whereas discarding the only
     * copy of the passphrase leaves the existing encrypted DB permanently unopenable. NOTE that
     * even a justified regeneration does NOT rebuild the old DB — Room simply fails to open it; the
     * recovery path for that state is deleting the DB and restoring the user's backup.
     */
    fun databasePassphrase(): ByteArray = synchronized(lock) {
        val blob = wrappedFile.takeIf { it.exists() }?.readBytes()
        // Missing or structurally truncated (< [12-byte IV][16-byte GCM tag]) blobs are provably
        // unrecoverable without any Keystore call; the Keystore key itself may still be fine.
        if (blob == null || blob.size < WRAPPED_MIN_BYTES) return@synchronized generateAndStore()
        try {
            KeystoreWrap.unwrap(KEY_ALIAS, blob)
        } catch (e: Exception) {
            // AEADBadTagException is a BadPaddingException; a deleted alias surfaces the same way
            // (KeystoreWrap auto-creates a fresh key, which then fails the tag check).
            if (e !is BadPaddingException && e !is UnrecoverableKeyException) throw e
            AppLog.w(TAG, "wrapped passphrase unrecoverable (${e.message}); regenerating")
            KeystoreWrap.deleteKey(KEY_ALIAS)
            wrappedFile.delete()
            generateAndStore()
        }
    }

    private fun generateAndStore(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        wrappedFile.parentFile?.mkdirs()
        // Write-then-rename so a crash mid-write can't leave a truncated blob behind (which would
        // read back as permanent key loss). rename(2) replaces the destination atomically.
        val tmp = File(wrappedFile.parentFile, wrappedFile.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(KeystoreWrap.wrap(KEY_ALIAS, passphrase))
            out.fd.sync()
        }
        if (!tmp.renameTo(wrappedFile)) {
            tmp.delete()
            error("could not move wrapped passphrase into place")
        }
        return passphrase
    }

    private companion object {
        const val TAG = "DatabaseKeyStore"
        const val KEY_ALIAS = "pathline_db_master"
        const val WRAPPED_FILE = "db_passphrase.bin"
        const val PASSPHRASE_BYTES = 32

        /** Smallest possible [KeystoreWrap] blob: 12-byte GCM IV + 16-byte tag (empty plaintext). */
        const val WRAPPED_MIN_BYTES = 12 + 16
    }
}
