package net.extrawdw.apps.locationhistory.security

import android.content.Context
import net.extrawdw.apps.locationhistory.core.AppLog
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Helpers for keying SQLCipher consistently and for migrating a pre-existing plaintext database to
 * an encrypted one.
 *
 * We use SQLCipher **raw-key** mode: the 32-byte key from [DatabaseKeyStore] is formatted as the
 * literal `x'<hex>'`. SQLCipher recognises that passphrase shape and uses the bytes directly as the
 * key instead of running its PBKDF2 KDF over them. Formatting it identically everywhere (the Room
 * open-helper factory and the migration `ATTACH`) guarantees the same key derives the same database.
 */
object SqlCipherSupport {

    /** The SQLCipher passphrase bytes (`x'<hex>'` ASCII) for a 32-byte raw key. */
    fun passphrase(rawKey: ByteArray): ByteArray = keyLiteral(rawKey).toByteArray(Charsets.US_ASCII)

    private fun keyLiteral(rawKey: ByteArray): String =
        "x'" + rawKey.joinToString("") { "%02x".format(it) } + "'"

    /**
     * If [dbFile] exists and is an *unencrypted* SQLite database (left over from before encryption
     * was introduced), copy it into a new SQLCipher database keyed by [rawKey] and swap it into
     * place. On failure the plaintext DB and its sidecars are kept intact and the error rethrown:
     * the likely causes (disk-full during `sqlcipher_export`) are transient, and no backup is
     * verified to exist, so the app crashes this launch and retries the migration on the next one
     * rather than discarding the user's only copy of the data.
     */
    fun migratePlaintextIfNeeded(context: Context, dbFile: File, rawKey: ByteArray) {
        if (!dbFile.exists() || !looksLikePlaintextSqlite(dbFile)) return
        AppLog.i(TAG, "plaintext DB detected; migrating to encrypted")
        val encryptedTmp = File(dbFile.parentFile, dbFile.name + ".enc-migrate")
        encryptedTmp.delete()
        val key = keyLiteral(rawKey)
        try {
            // Empty password opens the legacy file without encryption.
            val src = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, "", null, null)
            try {
                src.rawExecSQL("ATTACH DATABASE '${encryptedTmp.absolutePath}' AS enc KEY \"$key\";")
                src.rawExecSQL("SELECT sqlcipher_export('enc');")
                src.rawExecSQL("DETACH DATABASE enc;")
            } finally {
                src.close()
            }
            // Drop the stale journal siblings (the open/close above already replayed and
            // checkpointed them), then swap the encrypted copy into place. rename(2) replaces the
            // destination atomically, so there is no window with neither database present.
            sidecars(dbFile).forEach { it.delete() }
            if (!encryptedTmp.renameTo(dbFile)) {
                error("could not swap encrypted database into place")
            }
            AppLog.i(TAG, "plaintext->encrypted migration complete")
        } catch (t: Throwable) {
            // Delete only the partial encrypted copy; the plaintext DB stays untouched so the
            // migration can retry next launch.
            AppLog.w(TAG, "plaintext migration failed (${t.message}); keeping plaintext DB for retry")
            encryptedTmp.delete()
            throw t
        }
    }

    private fun sidecars(dbFile: File): List<File> =
        listOf(
            File(dbFile.path + "-wal"),
            File(dbFile.path + "-shm"),
            File(dbFile.path + "-journal")
        )

    /** The 16-byte SQLite header magic: "SQLite format 3" followed by a NUL byte. */
    private val SQLITE_MAGIC: ByteArray = "SQLite format 3".toByteArray(Charsets.US_ASCII) + 0x00

    private fun looksLikePlaintextSqlite(file: File): Boolean = runCatching {
        if (file.length() < 16) return@runCatching false
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        header.contentEquals(SQLITE_MAGIC)
    }.getOrDefault(false)

    private const val TAG = "SqlCipher"
}
