package net.extrawdw.apps.locationhistory.backup

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.SystemClock
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.extrawdw.apps.locationhistory.core.AppLog
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class SafBackupStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val identities = object : DirectoryIdentities {
        private val prefs = context.getSharedPreferences("backup_document_ids", Context.MODE_PRIVATE)
        private fun key(parent: String, name: String) = backupHashHex("$parent\u0000$name".toByteArray())
        override fun get(parent: String, name: String): String? = prefs.getString(key(parent, name), null)
        override fun put(parent: String, name: String, id: String) {
            if (get(parent, name) != id && !prefs.edit().putString(key(parent, name), id).commit()) {
                throw BackupStorageException("Could not remember the backup folder identity")
            }
        }
        override fun remove(parent: String, name: String) {
            if (get(parent, name) != null && !prefs.edit().remove(key(parent, name)).commit()) {
                throw BackupStorageException("Could not forget the archived backup folder identity")
            }
        }
    }

    suspend fun open(treeUri: Uri, writable: Boolean = true): SafDir? = withContext(Dispatchers.IO) {
        if (!DocumentsContract.isTreeUri(treeUri)) return@withContext null
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val provider = AndroidBackupDocuments(context)
        try {
            val root = provider.metadata(uri.toString())
            if (!root.directory) null else {
                if (writable) provider.requireCreatePermission(uri)
                SafDir(provider, root, identities) { AppLog.i("Backup", it) }
            }
        } catch (_: SecurityException) { null }
    }
}

/** Cursor extras are part of the protocol; DocumentFile convenience queries discard them. */
internal class AndroidBackupDocuments(private val context: Context) : BackupDocuments {
    private val resolver get() = context.contentResolver
    private val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    override suspend fun list(parent: BackupDocument): List<BackupDocument> = withContext(Dispatchers.IO) {
        val uri = parent.id.toUri()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
        val changes = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { changes.trySend(Unit) }
        }
        val observed = mutableSetOf<Uri>()
        fun observe(target: Uri) {
            if (observed.add(target)) resolver.registerContentObserver(target, true, observer)
        }
        val deadline = SystemClock.elapsedRealtime() + 30_000
        try {
            observe(children)
            while (true) {
                currentCoroutineContext().ensureActive()
                var loading = false
                val rows: List<BackupDocument> = query(children) { cursor ->
                    cursor.notificationUris?.forEach { observe(it) }
                    if (cursor.extras.getString(DocumentsContract.EXTRA_ERROR) != null) {
                        throw BackupStorageException("The backup provider could not list a folder; retry later")
                    }
                    loading = cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
                    if (loading) emptyList() else buildList {
                        while (cursor.moveToNext()) add(document(cursor, uri))
                    }
                }
                if (!loading) return@withContext rows
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw BackupStorageException("The backup provider is still loading; retry later")
                withTimeoutOrNull(minOf(remaining, 1_000).milliseconds) { changes.receive() }
            }
            @Suppress("UNREACHABLE_CODE") emptyList()
        } finally { resolver.unregisterContentObserver(observer); changes.close() }
    }

    override suspend fun metadata(id: String): BackupDocument = query(id.toUri()) { cursor ->
        if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
            cursor.extras.getString(DocumentsContract.EXTRA_ERROR) != null) {
            throw BackupStorageException("Backup document metadata is unavailable; retry later")
        }
        if (!cursor.moveToFirst()) throw java.io.FileNotFoundException("Backup document no longer exists")
        document(cursor, id.toUri())
    }

    suspend fun requireCreatePermission(uri: Uri) = query(uri) { cursor ->
        if (!cursor.moveToFirst()) throw BackupStorageException("Backup folder metadata is unavailable")
        val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS))
        if (flags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) throw SecurityException("Read-only backup folder")
    }

    override suspend fun create(parent: BackupDocument, name: String, mime: String): BackupDocument = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val uri = DocumentsContract.createDocument(resolver, parent.id.toUri(), mime, name)
            ?: throw BackupStorageException("The backup provider could not create a document")
        BackupDocument(uri.toString(), name, mime == SafDir.DIRECTORY_MIME)
    }

    override suspend fun read(document: BackupDocument): ByteArray = runInterruptible(Dispatchers.IO) {
        resolver.openInputStream(document.id.toUri())?.use { it.readBytes() }
            ?: throw BackupStorageException("The backup provider could not open a file")
    }
    override suspend fun write(document: BackupDocument, block: (OutputStream) -> Unit) = runInterruptible(Dispatchers.IO) {
        resolver.openOutputStream(document.id.toUri(), "wt")?.use(block)
            ?: throw BackupStorageException("The backup provider could not write a file")
    }
    override suspend fun delete(document: BackupDocument) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!DocumentsContract.deleteDocument(resolver, document.id.toUri())) {
            throw BackupStorageException("The backup provider could not delete a backup document")
        }
    }

    override suspend fun move(document: BackupDocument, sourceParent: BackupDocument,
        targetParent: BackupDocument): BackupDocument = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val uri = DocumentsContract.moveDocument(resolver, document.id.toUri(), sourceParent.id.toUri(), targetParent.id.toUri())
            ?: throw BackupStorageException("The backup provider could not move ${document.name}")
        document.copy(id = uri.toString())
    }

    private fun document(cursor: android.database.Cursor, tree: Uri): BackupDocument {
        fun required(column: String): String = cursor.getString(cursor.getColumnIndexOrThrow(column))
            ?: throw BackupStorageException("The backup provider returned incomplete document metadata")
        return BackupDocument(
            DocumentsContract.buildDocumentUriUsingTree(tree, required(Document.COLUMN_DOCUMENT_ID)).toString(),
            required(Document.COLUMN_DISPLAY_NAME), required(Document.COLUMN_MIME_TYPE) == Document.MIME_TYPE_DIR,
            cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0,
            cursor.getInt(cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS)) and Document.FLAG_SUPPORTS_MOVE != 0,
        )
    }

    private suspend fun <T : Any> query(uri: Uri, read: (android.database.Cursor) -> T): T = withContext(Dispatchers.IO) {
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<T> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    val result = resolver.query(uri, projection, null, null, null, signal)?.use(read)
                        ?: throw BackupStorageException("The backup provider returned no directory result; retry later")
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        } ?: throw BackupStorageException("The backup provider query timed out; retry later")
    }
}
