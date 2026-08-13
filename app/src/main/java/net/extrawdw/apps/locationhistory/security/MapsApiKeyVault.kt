package net.extrawdw.apps.locationhistory.security

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local storage for the Google Maps Platform key supplied by the user.
 *
 * The plaintext key is never put in DataStore, Room, Android backup, BuildConfig, or logs. Only a
 * Keystore-wrapped blob is persisted in [Context.getNoBackupFilesDir]. The optional Pathline export
 * path obtains the plaintext explicitly and includes it only inside an encrypted backup.
 */
@Singleton
class MapsApiKeyVault @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val keyFile: File get() = File(context.noBackupFilesDir, KEY_FILE)
    private val configuredState = MutableStateFlow(readKey() != null)

    val configured: StateFlow<Boolean> = configuredState.asStateFlow()

    @Synchronized
    fun store(apiKey: String) {
        val normalized = apiKey.trim()
        require(isPlausibleApiKey(normalized)) { "Invalid Google Maps Platform API key" }
        keyFile.parentFile?.mkdirs()
        val atomic = AtomicFile(keyFile)
        val output = atomic.startWrite()
        try {
            output.write(KeystoreWrap.wrap(KEY_ALIAS, normalized.encodeToByteArray()))
            atomic.finishWrite(output)
            configuredState.value = true
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    /** Returns the plaintext only to an API client or the explicitly enabled encrypted exporter. */
    @Synchronized
    fun apiKey(): String? = readKey()

    @Synchronized
    fun clear() {
        keyFile.delete()
        KeystoreWrap.deleteKey(KEY_ALIAS)
        configuredState.value = false
    }

    /** Non-secret identity used to notice a replaced key without retaining another plaintext copy. */
    fun fingerprint(apiKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(apiKey.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun readKey(): String? {
        if (!keyFile.exists()) return null
        return runCatching {
            KeystoreWrap.unwrap(KEY_ALIAS, keyFile.readBytes()).decodeToString()
                .takeIf(::isPlausibleApiKey)
        }.getOrNull()
    }

    private fun isPlausibleApiKey(value: String): Boolean =
        value.length in 20..200 && value.none(Char::isWhitespace)

    private companion object {
        const val KEY_ALIAS = "pathline_user_maps_platform_key"
        const val KEY_FILE = "maps_platform_api_key.bin"
    }
}
