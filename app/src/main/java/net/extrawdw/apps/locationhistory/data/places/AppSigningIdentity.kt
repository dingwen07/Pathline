package net.extrawdw.apps.locationhistory.data.places

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class SigningCertificateFingerprint(
    /** Uppercase hexadecimal without separators, as expected by gcloud and Android API headers. */
    val sha1: String,
    /** Human-readable colon-delimited fingerprint. */
    val sha1Display: String,
    val sha256Display: String,
)

enum class GcloudCommandShell {
    POSIX,
    POWERSHELL,
}

/** Reads the certificates that signed the currently installed APK and builds the setup command. */
@Singleton
class AppSigningIdentity @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val packageName: String get() = context.packageName

    fun installedCertificates(): List<SigningCertificateFingerprint> {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        val signing = info.signingInfo ?: return emptyList()
        val signatures = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners
        } else {
            signing.signingCertificateHistory
        }
        return signatures.map { signature ->
            val bytes = signature.toByteArray()
            val sha1 = digest("SHA-1", bytes)
            SigningCertificateFingerprint(
                sha1 = sha1,
                sha1Display = colonDelimited(sha1),
                sha256Display = colonDelimited(digest("SHA-256", bytes)),
            )
        }.distinctBy { it.sha1 }
    }

    /** SHA-1 of the APK's current signer for `X-Android-Cert`. */
    fun currentSha1(): String {
        // signingCertificateHistory is oldest -> newest after key rotation, so it cannot identify
        // the active APK signer by taking its first item. apkContentsSigners is the current signer set.
        val signer = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        ).signingInfo?.apkContentsSigners?.firstOrNull()
            ?: error("No signing certificate for ${context.packageName}")
        return digest("SHA-1", signer.toByteArray())
    }

    /**
     * A single physical line suitable for Copy or Android's text share sheet. It contains no API
     * key: gcloud prints the newly-created key only after the user explicitly runs the command.
     */
    fun gcloudSetupCommand(projectId: String, shell: GcloudCommandShell): String? {
        val project = projectId.trim()
        if (!PROJECT_ID.matches(project)) return null
        val signers = buildSet {
            if (packageName == OFFICIAL_PACKAGE) addAll(OFFICIAL_SIGNER_SHA1S)
            addAll(installedCertificates().map { it.sha1 })
        }.sorted()
        if (signers.isEmpty()) return null

        return buildGcloudSetupCommand(project, packageName, signers, USER_KEY_ID, shell)
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString("") { "%02X".format(it) }

    private fun colonDelimited(hex: String): String = hex.chunked(2).joinToString(":")

    companion object {
        private val PROJECT_ID = Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")
        private const val OFFICIAL_PACKAGE = "net.extrawdw.apps.locationhistory"
        private const val USER_KEY_ID = "pathline-android"

        /** Public certificate fingerprints already authorized for Play, upload/sideload, and debug. */
        val OFFICIAL_SIGNER_SHA1S: Set<String> = setOf(
            "753E5171DC989818F91D5F9FDDD7BCF6A48B5A8F",
            "CEA61831BB5BEC1AF562E961897618BD4C2C3D44",
            "44DF4626CC8316DA9EA06A4340CE93CE8EA1A4EF",
        )
    }
}

/** Builds one physical command line for the selected shell without embedding an API key. */
internal fun buildGcloudSetupCommand(
    project: String,
    packageName: String,
    signers: List<String>,
    keyId: String,
    shell: GcloudCommandShell,
): String {
    val allowed = signers.joinToString(" ") { sha1 ->
        "--allowed-application='sha1_fingerprint=$sha1,package_name=$packageName'"
    }
    val enable = "gcloud services enable apikeys.googleapis.com places.googleapis.com " +
        "routes.googleapis.com --project='$project'"
    val describe = "gcloud services api-keys describe '$keyId' " +
        "--project='$project' --format='value(name)'"
    val create = "gcloud services api-keys create --project='$project' " +
        "--display-name='Pathline Places and Routes' --key-id='$keyId' " +
        "--api-target='service=places.googleapis.com' " +
        "--api-target='service=routes.googleapis.com' $allowed"
    val update = "gcloud services api-keys update '$keyId' --project='$project' " +
        "--api-target='service=places.googleapis.com' " +
        "--api-target='service=routes.googleapis.com' $allowed"
    val getKey = "gcloud services api-keys get-key-string '$keyId' " +
        "--project='$project' --format='value(keyString)'"

    return when (shell) {
        GcloudCommandShell.POSIX ->
            "$enable && ($describe >/dev/null 2>&1 || $create >/dev/null) && " +
                "$update >/dev/null && $getKey"
        GcloudCommandShell.POWERSHELL ->
            "$enable; if (\$LASTEXITCODE -eq 0) { $describe *> \$null; " +
                "if (\$LASTEXITCODE -ne 0) { $create > \$null }; " +
                "if (\$LASTEXITCODE -eq 0) { $update > \$null; " +
                "if (\$LASTEXITCODE -eq 0) { $getKey } } }"
    }
}
