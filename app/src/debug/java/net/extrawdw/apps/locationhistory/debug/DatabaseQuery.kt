package net.extrawdw.apps.locationhistory.debug

import android.annotation.SuppressLint
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.Base64
import android.database.Cursor
import net.extrawdw.apps.locationhistory.BuildConfig
import net.extrawdw.apps.locationhistory.security.SqlCipherSupport
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.system.exitProcess

/**
 * Debug-only ADB entry point. Run app_process with this APK as CLASSPATH, from `run-as`'s
 * application data directory. Does not initialize Application, Room, recording, or telemetry.
 * The first argument is a SELECT query; remaining arguments bind its positional parameters.
 */
object DatabaseQuery {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            check(BuildConfig.DEBUG) { "Only available in debug builds" }
            require(args.isNotEmpty()) { "Usage: DatabaseQuery 'SELECT ... WHERE timestampMs >= ?' [bind values...]" }
            val directory = File(".").canonicalFile
            check(directory.name == BuildConfig.APPLICATION_ID &&
                Os.stat(directory.path).st_uid == Process.myUid() && Process.myUid() >= 10_000
            ) { "Run from the application data directory using run-as ${BuildConfig.APPLICATION_ID}" }
            loadSqlCipher()
            val rawKey = readExistingKey(directory)
            val passphrase = try { SqlCipherSupport.passphrase(rawKey) } finally { rawKey.fill(0) }
            try {
                query(File(directory, "databases/pathline.db"), passphrase, args[0], args.drop(1).toTypedArray(), ::println)
            } finally {
                passphrase.fill(0)
            }
        } catch (error: Exception) {
            System.err.println("Database query failed: ${error.message}")
            exitProcess(1)
        }
    }

    internal fun query(
        file: File,
        passphrase: ByteArray,
        sql: String,
        arguments: Array<String> = emptyArray(),
        output: (String) -> Unit,
    ) {
        require(file.isFile) { "Database does not exist" }
        // A SELECT subquery accepts joins, aggregates, CTEs and pragma table-valued functions,
        // but cannot execute top-level writes, ATTACH, or configuration PRAGMAs.
        val select = "SELECT * FROM (\n${sql.trim().removeSuffix(";")}\n) AS debug_query"
        SQLiteDatabase.openDatabase(
            file.path, passphrase, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            { _, error -> throw IllegalStateException("Read-only query stopped on database error", error) },
            null,
        ).use { database ->
            database.rawQuery(select, arguments).use { cursor ->
                output(JSONObject().put("columns", JSONArray(cursor.columnNames.toList())).toString())
                var count = 0
                while (count < MAX_ROWS && cursor.moveToNext()) {
                    val row = JSONArray()
                    for (column in 0 until cursor.columnCount) {
                        row.put(when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column).let { if (it.isFinite()) it else it.toString() }
                            Cursor.FIELD_TYPE_BLOB -> JSONObject().put("base64", Base64.encodeToString(cursor.getBlob(column), Base64.NO_WRAP))
                            else -> cursor.getString(column)
                        })
                    }
                    output(row.toString())
                    count++
                }
                output(JSONObject().put("rows", count).put("truncated", count == MAX_ROWS && cursor.moveToNext()).toString())
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun readExistingKey(directory: File): ByteArray {
        // app_process has no Application initialization to register Android's Keystore provider.
        if (Security.getProvider("AndroidKeyStore") == null) {
            Class.forName("android.security.keystore2.AndroidKeyStoreProvider").getMethod("install").invoke(null)
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey("pathline_db_master", null) as? SecretKey
            ?: error("Existing database key unavailable")
        val wrapped = File(directory, "no_backup/db_passphrase.bin").readBytes()
        require(wrapped.size >= 28) { "Invalid wrapped database key" }
        // Never call key creation, migration, or corruption-recovery paths from this diagnostic.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode") // app_process needs the native library from its own APK explicitly.
    private fun loadSqlCipher() {
        val apk = System.getProperty("java.class.path").split(File.pathSeparator)
            .singleOrNull { it.endsWith("/base.apk") } ?: error("Set CLASSPATH to the installed base.apk")
        for (abi in Build.SUPPORTED_ABIS) {
            try {
                System.load("$apk!/lib/$abi/libsqlcipher.so")
                return
            } catch (_: UnsatisfiedLinkError) {
                // A universal APK can contain several device ABIs.
            }
        }
        error("Could not load SQLCipher from the installed APK")
    }

    private const val MAX_ROWS = 10_000
}
