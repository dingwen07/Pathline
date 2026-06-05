package net.extrawdw.apps.locationhistory.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import net.extrawdw.apps.locationhistory.R

/**
 * Best-effort, human-readable label for a SAF backup/GPX destination tree, instead of the raw and
 * often ugly document id (e.g. `primary:Backups/Pathline`, or Google Drive's opaque
 * `acc=1;doc=encoded=…`).
 *
 * Format is `Name (detail)`, e.g. `Internal storage (primary:Backups/Pathline)`.
 * - `com.android.externalstorage.documents` is special-cased: the `volume:path` document id names
 *   the volume ("Internal storage" for primary/home, else "External storage") and the full id is kept
 *   in parentheses.
 * - Any other provider is resolved to the owning app's name via [PackageManager.resolveContentProvider]
 *   (visible to us via the scoped `DOCUMENTS_PROVIDER` `<queries>` entry in the manifest — no
 *   `QUERY_ALL_PACKAGES`). Its document id is opaque, so only the app name + chosen subdir are shown.
 *
 * [subdir] is the user-chosen folder under the tree (backups use it; GPX passes null).
 */
object SafDestination {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun describe(context: Context, treeUri: String, subdir: String?): String {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull()
            ?: return context.getString(R.string.dest_unknown)
        val authority = uri.authority
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()

        val name: String
        val detailBase: String // readable location shown in parens; "" = nothing to show
        if (authority == EXTERNAL_STORAGE_AUTHORITY) {
            val volume = (docId ?: "").substringBefore(':')
            name = context.getString(
                if (volume.equals("primary", ignoreCase = true) || volume.equals("home", ignoreCase = true)) {
                    R.string.dest_internal_storage
                } else {
                    R.string.dest_external_storage
                },
            )
            detailBase = docId.orEmpty() // keep the full "volume:path" id, e.g. primary:Backups/Pathline
        } else {
            name = resolveAppLabel(context, authority) ?: context.getString(R.string.dest_unknown)
            detailBase = "" // opaque document id — don't surface it
        }

        // The actual root is tree/subdir, so fold the subdir into the detail.
        val sub = subdir?.trim()?.trim('/').orEmpty()
        val detail = when {
            detailBase.isEmpty() -> sub
            sub.isEmpty() -> detailBase
            detailBase.endsWith(':') || detailBase.endsWith('/') -> detailBase + sub
            else -> "$detailBase/$sub"
        }
        return if (detail.isEmpty()) name else "$name ($detail)"
    }

    /** App label of the package that owns [authority], or null if it can't be resolved. */
    private fun resolveAppLabel(context: Context, authority: String?): String? {
        authority ?: return null
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = runCatching { pm.resolveContentProvider(authority, 0) }.getOrNull() ?: return null
        return runCatching { pm.getApplicationLabel(info.applicationInfo).toString() }.getOrNull()
    }
}
