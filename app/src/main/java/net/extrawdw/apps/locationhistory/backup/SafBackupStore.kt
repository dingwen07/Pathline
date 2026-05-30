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
    private val children: MutableMap<String, DocumentFile> by lazy {
        doc.listFiles().filter { it.name != null }.associateByTo(HashMap()) { it.name!! }
    }

    /** Find or create a subdirectory. */
    fun childDir(name: String): SafDir {
        val existing = children[name]
        val dir = if (existing != null && existing.isDirectory) {
            existing
        } else {
            existing?.delete() // a stale file where a dir should be
            doc.createDirectory(name)?.also { children[name] = it }
                ?: error("could not create backup subdirectory '$name'")
        }
        return SafDir(context, dir)
    }

    fun exists(name: String): Boolean = children[name]?.exists() == true

    fun fileNames(): List<String> = children.keys.toList()

    /** (Re)write a file atomically-ish: drop any existing entry, then create and stream into it. */
    fun writeFile(name: String, mime: String, write: (OutputStream) -> Unit) {
        children[name]?.delete()
        children.remove(name)
        val file = doc.createFile(mime, name) ?: error("could not create backup file '$name'")
        // Some providers append an extension/suffix; track by the real returned name.
        file.name?.let { children[it] = file }
        val resolved = file.uri
        context.contentResolver.openOutputStream(resolved, "wt")?.use(write)
            ?: error("could not open output stream for '$name'")
    }

    fun readFile(name: String): InputStream? {
        val file = children[name]?.takeIf { it.exists() } ?: return null
        return context.contentResolver.openInputStream(file.uri)
    }

    fun deleteFile(name: String) {
        children[name]?.delete()
        children.remove(name)
    }
}
