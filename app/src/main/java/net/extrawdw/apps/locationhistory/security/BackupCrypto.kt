package net.extrawdw.apps.locationhistory.security

import com.google.firebase.perf.metrics.AddTrace
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** How a backup's contents are protected at rest. */
enum class BackupEncryption { NONE, PASSWORD, PASSKEY }

/**
 * Envelope-encryption layer for backups.
 *
 * A random 256-bit **data encryption key (DEK)** encrypts every partition/snapshot file with
 * AES-256-GCM. The DEK is never stored in the clear; it is *wrapped* into a key slot in the
 * manifest. Two portable slot types exist:
 *  - **password** — PBKDF2-HMAC-SHA256 derives a key-encryption key from the user's password.
 *  - **passkey (PRF)** — a WebAuthn passkey's PRF extension yields a stable 32-byte secret (keyed
 *    by the salt stored in the slot) that is used directly as the key-encryption key. Synced by the
 *    platform password manager, so it works on the user's other devices with no password to recall.
 *
 * The slot list is deliberately a single slot today; it is structured to grow into multiple slots
 * (e.g. password + passkey + recovery-key all wrapping the same DEK) without re-encrypting data.
 *
 * When encryption is disabled the manifest records [BackupEncryption.NONE] and files are written as
 * plaintext (still gzip-compressed) — there is no DEK.
 */
object BackupCrypto {

    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val DEK_BYTES = 32
    private const val SALT_BYTES = 16
    private const val PBKDF2_ITERATIONS = 210_000

    /** Length of the PRF eval input (salt) a passkey slot requests from the authenticator. */
    const val PRF_SALT_BYTES = 32

    /** Length of the PRF output a passkey returns; used directly as the key-encryption key. */
    const val PRF_SECRET_BYTES = 32

    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    // -- Header construction ------------------------------------------------------------------

    fun plaintextHeader(): CryptoHeader = CryptoHeader(mode = BackupEncryption.NONE)

    fun createPasswordHeader(password: CharArray): Pair<CryptoHeader, ByteArray> {
        val dek = randomBytes(DEK_BYTES)
        val salt = randomBytes(SALT_BYTES)
        val kek = deriveKek(password, salt, PBKDF2_ITERATIONS)
        val header = CryptoHeader(
            mode = BackupEncryption.PASSWORD,
            password = PasswordSlot(
                saltB64 = b64(salt),
                iterations = PBKDF2_ITERATIONS,
                wrappedDekB64 = b64(aesGcmSeal(kek, dek))
            ),
        )
        return header to dek
    }

    /**
     * Build a passkey-protected header. [prfSecret] is the 32-byte PRF output the authenticator
     * produced for [prfSalt]; restore must replay the same [prfSalt] to recover the same secret.
     */
    fun createPasskeyHeader(
        prfSecret: ByteArray,
        prfSalt: ByteArray,
        credentialId: String?
    ): Pair<CryptoHeader, ByteArray> {
        val dek = randomBytes(DEK_BYTES)
        val header = CryptoHeader(
            mode = BackupEncryption.PASSKEY,
            passkey = PasskeySlot(
                prfSaltB64 = b64(prfSalt),
                wrappedDekB64 = b64(aesGcmSeal(prfSecret, dek)),
                credentialId = credentialId,
            ),
        )
        return header to dek
    }

    // -- DEK recovery -------------------------------------------------------------------------

    /**
     * Recover the DEK described by [header]. Returns null for an unencrypted backup. Supply the
     * matching credential: [password] for a password slot, [prfSecret] for a passkey slot. Throws
     * (GCM tag mismatch) if the credential is wrong.
     */
    fun openDek(
        header: CryptoHeader,
        password: CharArray? = null,
        prfSecret: ByteArray? = null
    ): ByteArray? =
        when (header.mode) {
            BackupEncryption.NONE -> null
            BackupEncryption.PASSWORD -> {
                val slot = header.password ?: error("password backup is missing its key slot")
                requireNotNull(password) { "password required to open this backup" }
                aesGcmOpen(
                    deriveKek(password, unb64(slot.saltB64), slot.iterations),
                    unb64(slot.wrappedDekB64)
                )
            }

            BackupEncryption.PASSKEY -> {
                val slot = header.passkey ?: error("passkey backup is missing its key slot")
                requireNotNull(prfSecret) { "passkey PRF secret required to open this backup" }
                aesGcmOpen(prfSecret, unb64(slot.wrappedDekB64))
            }
        }

    /** The PRF eval salt a passkey-protected backup expects at restore time. */
    fun prfSaltOf(header: CryptoHeader): ByteArray? = header.passkey?.let { unb64(it.prfSaltB64) }

    // PBKDF2 is intentionally slow; @AddTrace surfaces its real per-device cost in Performance
    // Monitoring. Duration-only: no password or salt material is read, named, or uploaded.
    @AddTrace(name = "dek_derivation")
    private fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, DEK_BYTES * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun aesGcmSeal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun aesGcmOpen(key: ByteArray, blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val ct = blob.copyOfRange(GCM_IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(ct)
    }

    /**
     * Per-file streaming cipher. When [dek] is null the streams pass through unchanged (no
     * encryption). Otherwise each file is `[12-byte IV][AES-256-GCM ciphertext+tag]`.
     */
    class PartitionCipher(private val dek: ByteArray?) {

        val encrypted: Boolean get() = dek != null

        fun wrap(out: OutputStream): OutputStream {
            val key = dek ?: return out
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            out.write(cipher.iv)
            out.flush()
            return CipherOutputStream(out, cipher)
        }

        fun unwrap(input: InputStream): InputStream {
            val key = dek ?: return input
            val iv = ByteArray(GCM_IV_BYTES)
            var read = 0
            while (read < GCM_IV_BYTES) {
                val r = input.read(iv, read, GCM_IV_BYTES - read)
                if (r < 0) error("backup file truncated before IV")
                read += r
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            return CipherInputStream(input, cipher)
        }
    }
}

/** Serializable crypto descriptor stored in the backup manifest. */
@Serializable
data class CryptoHeader(
    val version: Int = 1,
    val mode: BackupEncryption,
    val password: PasswordSlot? = null,
    val passkey: PasskeySlot? = null,
)

@Serializable
data class PasswordSlot(
    val kdf: String = "PBKDF2WithHmacSHA256",
    val saltB64: String,
    val iterations: Int,
    val wrappedDekB64: String,
)

@Serializable
data class PasskeySlot(
    /** PRF eval input that must be replayed to the authenticator at restore to reproduce the secret. */
    val prfSaltB64: String,
    val wrappedDekB64: String,
    /** Credential id of the passkey used; when present, restore requires this exact passkey. */
    val credentialId: String? = null,
)
