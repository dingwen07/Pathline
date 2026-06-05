package net.extrawdw.apps.locationhistory.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over the Storage Access Framework that gives the backup engine a small file-system
 * style API rooted at a user-chosen tree (which may live on Google Drive, Dropbox, local storage,
 * an SD card — anything with a SAF provider). No Google APIs, OAuth, or app registration involved.
 *
 * DocumentFile lookups are slow (each is a content-provider query), so directory listings are
 * cached per [SafDir] for the lifetime of one backup run.
 */
@Singleton
class SafBackupStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** Resolve [treeUri] to a writable root directory, or null if the grant is gone/read-only. */
    fun open(treeUri: Uri): SafDir? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!root.isDirectory || !root.canWrite()) return null
        return SafDir(context, root)
    }
}

class SafDir internal constructor(
    private val context: Context,
    private val doc: DocumentFile,
) {
    // A display name can map to MULTIPLE documents: Google Drive (and some other providers) permit
    // several files with the same name in one folder. We must track them all — otherwise a
    // delete-then-create write removes only one copy and duplicates pile up across incremental runs.
    private val children: MutableMap<String, MutableList<DocumentFile>> by lazy {
        val map = HashMap<String, MutableList<DocumentFile>>()
        doc.listFiles().forEach { f -> f.name?.let { map.getOrPut(it) { mutableListOf() }.add(f) } }
        map
    }

    /** Resolve an existing subdirectory **without creating it**; null if it doesn't exist yet. */
    fun childDirOrNull(name: String): SafDir? {
        val existing = children[name]?.firstOrNull { it.isDirectory && it.exists() } ?: return null
        return SafDir(context, existing)
    }

    /** Find or create a subdirectory. */
    fun childDir(name: String): SafDir {
        children[name]?.firstOrNull { it.isDirectory && it.exists() }?.let { return SafDir(context, it) }
        // No directory by that name (possibly a stale file, or duplicates): clear them, then create.
        deleteAllNamed(name)
        val dir = doc.createDirectory(name)?.also { children[name] = mutableListOf(it) }
            ?: error("could not create backup subdirectory '$name'")
        return SafDir(context, dir)
    }

    fun exists(name: String): Boolean = children[name]?.any { it.exists() } == true

    fun fileNames(): List<String> = children.keys.toList()

    /** (Re)write a file: drop EVERY existing document with this name first (providers may allow dupes). */
    fun writeFile(name: String, mime: String, write: (OutputStream) -> Unit) {
        deleteAllNamed(name)
        val file = doc.createFile(mime, name) ?: error("could not create backup file '$name'")
        // Some providers append an extension/suffix; track by the real returned name.
        file.name?.let { children.getOrPut(it) { mutableListOf() }.add(file) }
        context.contentResolver.openOutputStream(file.uri, "wt")?.use(write)
            ?: error("could not open output stream for '$name'")
    }

    fun readFile(name: String): InputStream? {
        val file = children[name]?.firstOrNull { it.exists() } ?: return null
        return context.contentResolver.openInputStream(file.uri)
    }

    fun deleteFile(name: String) = deleteAllNamed(name)

    /** Delete every document with [name] (handles providers that allow duplicate display names). */
    private fun deleteAllNamed(name: String) {
        children.remove(name)?.forEach { it.delete() }
    }
}
