package net.extrawdw.apps.locationhistory.security

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import dagger.hilt.android.qualifiers.ApplicationContext
import net.extrawdw.apps.locationhistory.core.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a passkey PRF ceremony: the 32-byte secret plus the salt + credential it came from. */
data class PasskeyPrf(val secret: ByteArray, val salt: ByteArray, val credentialId: String?)

class PasskeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Drives WebAuthn passkeys via Credential Manager and reads the **PRF** extension output, which is a
 * stable 32-byte secret keyed by (passkey, salt). That secret encrypts the backup DEK, so a passkey
 * synced by the platform password manager unlocks the backup on the user's other devices with no
 * password to remember.
 *
 * The relying party is [RP_ID]; it must be associated with this app via a Digital Asset Links file
 * at `https://locationhistory.apps.extrawdw.net/.well-known/assetlinks.json`. No backend is needed —
 * the assertion is never verified server-side; only the local PRF output is consumed.
 *
 * NOTE: requires the live domain + a device with a PRF-capable provider (Google Password Manager).
 * It cannot run on an emulator without that setup. All methods need an Activity [Context].
 */
@Singleton
class PasskeyManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {
    private val urlEnc = Base64.getUrlEncoder().withoutPadding()
    private val urlDec = Base64.getUrlDecoder()

    /**
     * Obtain a PRF secret for setting up an encrypted backup: reuse an existing discoverable passkey
     * if the user has one for this RP, otherwise create a new one. Generates a fresh salt to store
     * in the backup's key slot.
     */
    suspend fun obtainForSetup(activityContext: Context): PasskeyPrf {
        val salt = BackupCrypto.randomBytes(BackupCrypto.PRF_SALT_BYTES)
        // Reuse an existing passkey if one is *immediately available* (preferImmediatelyAvailable
        // returns NoCredentialException with no UI when none exists, avoiding the confusing
        // "sign in another way" sheet), otherwise create a new one. Only an explicit cancel aborts.
        try {
            return runAssert(activityContext, salt, requireCredentialId = null, preferImmediate = true)
        } catch (e: GetCredentialCancellationException) {
            throw PasskeyException("Passkey setup was cancelled", e)
        } catch (e: NoCredentialException) {
            AppLog.i(TAG, "no reusable passkey; creating one")
        } catch (e: GetCredentialException) {
            AppLog.w(TAG, "reuse lookup failed (${e.message}); creating a new passkey")
        }
        return createWithPrf(activityContext, salt)
    }

    /**
     * Reproduce the PRF secret for a restore, replaying the salt stored in the backup. When
     * [credentialId] is known (from the backup's key slot) the request *requires that exact passkey*
     * via allowCredentials, so the right key is selected deterministically even if several exist.
     */
    suspend fun obtainForRestore(activityContext: Context, salt: ByteArray, credentialId: String? = null): PasskeyPrf =
        try {
            runAssert(activityContext, salt, requireCredentialId = credentialId, preferImmediate = false)
        } catch (e: GetCredentialException) {
            throw PasskeyException("Passkey unlock failed: ${e.message}", e)
        }

    // -- Create (registration) ----------------------------------------------------------------

    private suspend fun createWithPrf(activityContext: Context, salt: ByteArray): PasskeyPrf {
        val userId = BackupCrypto.randomBytes(16)
        val options = JSONObject().apply {
            put("challenge", urlEnc.encodeToString(BackupCrypto.randomBytes(32)))
            put("rp", JSONObject().put("id", RP_ID).put("name", RP_NAME))
            put("user", JSONObject()
                .put("id", urlEnc.encodeToString(userId))
                .put("name", USER_NAME)
                .put("displayName", USER_DISPLAY))
            put("pubKeyCredParams", JSONArray()
                .put(JSONObject().put("type", "public-key").put("alg", -7))
                .put(JSONObject().put("type", "public-key").put("alg", -257)))
            put("authenticatorSelection", JSONObject()
                .put("residentKey", "required")
                .put("requireResidentKey", true)
                .put("userVerification", "required"))
            put("extensions", prfExtension(salt))
        }
        val response = try {
            CredentialManager.create(appContext).createCredential(
                activityContext,
                CreatePublicKeyCredentialRequest(options.toString()),
            ) as CreatePublicKeyCredentialResponse
        } catch (t: Throwable) {
            throw PasskeyException("Could not create a passkey: ${t.message}", t)
        }
        val json = JSONObject(response.registrationResponseJson)
        val credentialId = json.optStringOrNull("id")
        val secret = prfResult(json)
        // Some authenticators don't return PRF results at creation — assert once to get them.
        return if (secret != null) {
            PasskeyPrf(secret, salt, credentialId)
        } else {
            runAssert(activityContext, salt, requireCredentialId = null, preferImmediate = false)
        }
    }

    // -- Get (assertion) ----------------------------------------------------------------------

    /**
     * Run a passkey assertion and read its PRF output. Lets [GetCredentialException] (incl.
     * [NoCredentialException], cancellation) propagate raw so callers can decide whether to fall
     * back to creating. [preferImmediate] returns [NoCredentialException] with no UI when nothing is
     * available (used for the silent reuse probe at setup).
     */
    private suspend fun runAssert(
        activityContext: Context, salt: ByteArray, requireCredentialId: String?, preferImmediate: Boolean,
    ): PasskeyPrf {
        val options = JSONObject().apply {
            put("challenge", urlEnc.encodeToString(BackupCrypto.randomBytes(32)))
            put("rpId", RP_ID)
            put("userVerification", "required")
            if (requireCredentialId != null) {
                // Require this exact passkey (its PRF secret encrypted the backup); avoids the
                // ambiguity of a discoverable picker when several Pathline passkeys exist.
                put("allowCredentials", JSONArray().put(
                    JSONObject().put("type", "public-key").put("id", requireCredentialId),
                ))
            }
            // else: discoverable — the platform offers the user's passkeys for this RP (setup/reuse).
            put("extensions", prfExtension(salt))
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetPublicKeyCredentialOption(options.toString()))
            .setPreferImmediatelyAvailableCredentials(preferImmediate)
            .build()
        val response = CredentialManager.create(appContext).getCredential(activityContext, request)
        val credential = response.credential as? PublicKeyCredential
            ?: throw PasskeyException("Unexpected credential type")
        val json = JSONObject(credential.authenticationResponseJson)
        val secret = prfResult(json)
            ?: throw PasskeyException("This passkey did not return a PRF secret (provider may not support PRF)")
        return PasskeyPrf(secret, salt, json.optStringOrNull("id"))
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun prfExtension(salt: ByteArray): JSONObject =
        JSONObject().put(
            "prf",
            JSONObject().put("eval", JSONObject().put("first", urlEnc.encodeToString(salt))),
        )

    /** Extract clientExtensionResults.prf.results.first (base64url) -> 32 bytes, if present. */
    private fun prfResult(responseJson: JSONObject): ByteArray? {
        val first = responseJson
            .optJSONObject("clientExtensionResults")
            ?.optJSONObject("prf")
            ?.optJSONObject("results")
            ?.optStringOrNull("first")
            ?: return null
        return runCatching { urlDec.decode(first) }.getOrNull()
    }

    companion object {
        const val RP_ID = "locationhistory.apps.extrawdw.net"
        private const val RP_NAME = "Pathline"
        // Fixed, non-cosmetic identifiers. The user-facing label can be renamed by the user in their
        // password manager; we don't manage it. user.id stays a random opaque handle (below).
        private const val USER_NAME = "pathline-backup"
        private const val USER_DISPLAY = "Pathline Backup"
        private const val TAG = "PasskeyManager"
    }
}
