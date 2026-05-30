package net.extrawdw.apps.locationhistory.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import net.extrawdw.apps.locationhistory.core.AppLog
import java.io.File
import java.security.SecureRandom
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
     * The 32-byte database passphrase. Generates + wraps a new one on first use. If the Keystore key
     * was lost (e.g. a lock-credential reset invalidated it) a fresh passphrase is generated; the
     * old DB then fails to open and is rebuilt — raw history is recoverable from the user's backup.
     */
    fun databasePassphrase(): ByteArray = synchronized(lock) {
        if (wrappedFile.exists()) {
            runCatching { KeystoreWrap.unwrap(KEY_ALIAS, wrappedFile.readBytes()) }
                .getOrElse {
                    AppLog.w(TAG, "passphrase unwrap failed (${it.message}); regenerating")
                    KeystoreWrap.deleteKey(KEY_ALIAS)
                    wrappedFile.delete()
                    generateAndStore()
                }
        } else {
            generateAndStore()
        }
    }

    private fun generateAndStore(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        wrappedFile.parentFile?.mkdirs()
        wrappedFile.writeBytes(KeystoreWrap.wrap(KEY_ALIAS, passphrase))
        return passphrase
    }

    private companion object {
        const val TAG = "DatabaseKeyStore"
        const val KEY_ALIAS = "pathline_db_master"
        const val WRAPPED_FILE = "db_passphrase.bin"
        const val PASSPHRASE_BYTES = 32
    }
}
