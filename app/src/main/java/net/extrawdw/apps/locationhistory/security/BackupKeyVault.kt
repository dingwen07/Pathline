package net.extrawdw.apps.locationhistory.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a device-local, Keystore-wrapped copy of the backup **data-encryption key (DEK)** so the
 * background backup worker can keep encrypting incremental partitions without prompting for the
 * password every run. This is the "convenience slot in addition to the portable slots" — it is
 * device-bound and useless on another device, so restore on a new device still goes through the
 * password slot in the manifest.
 *
 * Stored in the no-backup files dir; even the wrapped blob never leaves the device via cloud backup.
 */
@Singleton
class BackupKeyVault @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val dekFile: File get() = File(context.noBackupFilesDir, DEK_FILE)
    private val pwdFile: File get() = File(context.noBackupFilesDir, PWD_FILE)

    /** Persist the DEK, wrapped by the device Keystore key. */
    fun store(dek: ByteArray) {
        dekFile.parentFile?.mkdirs()
        dekFile.writeBytes(KeystoreWrap.wrap(KEY_ALIAS, dek))
    }

    /** The locally-cached DEK, or null if none is stored or the Keystore key was lost. */
    fun localDek(): ByteArray? {
        if (!dekFile.exists()) return null
        return runCatching { KeystoreWrap.unwrap(KEY_ALIAS, dekFile.readBytes()) }.getOrNull()
    }

    /**
     * Persist the backup **password** itself, Keystore-wrapped, so this device can re-derive keys
     * and pre-fill restore without re-prompting. Device-bound and useless if copied off-device. The
     * portable password slot in the manifest remains the cross-device recovery path.
     */
    fun storePassword(password: CharArray) {
        pwdFile.parentFile?.mkdirs()
        pwdFile.writeBytes(
            KeystoreWrap.wrap(
                KEY_ALIAS,
                String(password).toByteArray(Charsets.UTF_8)
            )
        )
    }

    /** The locally-cached password, or null if none is stored / the Keystore key was lost. */
    fun localPassword(): CharArray? {
        if (!pwdFile.exists()) return null
        return runCatching {
            String(
                KeystoreWrap.unwrap(KEY_ALIAS, pwdFile.readBytes()),
                Charsets.UTF_8
            ).toCharArray()
        }.getOrNull()
    }

    /** Forget the local DEK + password (e.g. when the user disables or changes encryption). */
    fun clear() {
        dekFile.delete()
        pwdFile.delete()
        KeystoreWrap.deleteKey(KEY_ALIAS)
    }

    /** Forget only the cached password (e.g. switching from password to passkey encryption). */
    fun clearPasswordOnly() {
        pwdFile.delete()
    }

    private companion object {
        const val KEY_ALIAS = "pathline_backup_dek"
        const val DEK_FILE = "backup_dek.bin"
        const val PWD_FILE = "backup_pwd.bin"
    }
}
