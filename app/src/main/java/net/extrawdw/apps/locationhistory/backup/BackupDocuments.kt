package net.extrawdw.apps.locationhistory.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.FileNotFoundException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

internal data class BackupDocument(val id: String, val name: String, val directory: Boolean,
    val modifiedAtMs: Long = 0, val supportsMove: Boolean = false)

/** Listings must be complete or throw, never return an uncertain empty result. */
internal interface BackupDocuments {
    suspend fun list(parent: BackupDocument): List<BackupDocument>
    suspend fun metadata(id: String): BackupDocument
    suspend fun create(parent: BackupDocument, name: String, mime: String): BackupDocument
    suspend fun read(document: BackupDocument): ByteArray
    suspend fun write(document: BackupDocument, block: (OutputStream) -> Unit)
    suspend fun delete(document: BackupDocument)
    suspend fun move(document: BackupDocument, sourceParent: BackupDocument, targetParent: BackupDocument): BackupDocument
}

internal interface DirectoryIdentities {
    fun get(parent: String, name: String): String?
    fun put(parent: String, name: String, id: String)
    fun remove(parent: String, name: String)
    object None : DirectoryIdentities {
        override fun get(parent: String, name: String): String? = null
        override fun put(parent: String, name: String, id: String) = Unit
        override fun remove(parent: String, name: String) = Unit
    }
}

internal class BackupStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)
/** Complete provider results prove missing/corrupt backup content; a local full backup can repair it. */
internal class BackupContentException(message: String, cause: Throwable? = null) : IOException(message, cause)
internal fun backupHash(bytes: ByteArray): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
internal fun backupHashHex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal class SafSession(val provider: BackupDocuments, val identities: DirectoryIdentities, val log: (String) -> Unit) {
    private val listings = mutableMapOf<String, MutableList<BackupDocument>>()
    val directories = mutableMapOf<List<String>, SafDir>()
    // Per-operation evidence only; every new backup opens a new session.
    val verifiedHashes = mutableMapOf<String, String>()
    suspend fun children(parent: BackupDocument): MutableList<BackupDocument> =
        listings.getOrPut(parent.id) { provider.list(parent).toMutableList() }
    fun invalidate(parent: BackupDocument) { listings.remove(parent.id) }
    fun clear() { listings.clear(); directories.clear(); verifiedHashes.clear() }
}

/** Logical directories include legacy duplicate folders. Reads resolve by hash, writes pin an ID. */
class SafDir internal constructor(private val session: SafSession, internal val folders: List<BackupDocument>) {
    internal constructor(provider: BackupDocuments, root: BackupDocument, identities: DirectoryIdentities = DirectoryIdentities.None,
        log: (String) -> Unit = {}) : this(SafSession(provider, identities, log), listOf(root))
    private val provider get() = session.provider
    internal val canonical get() = folders.first()
    internal suspend fun documents(): List<BackupDocument> = folders.flatMap { session.children(it) }.distinctBy { it.id }
    suspend fun fileNames(): List<String> = documents().filterNot { it.directory }.map { it.name }.distinct()
    suspend fun childDirOrNull(name: String): SafDir? = resolveDirectory(name, false)
    suspend fun childDir(name: String): SafDir = requireNotNull(resolveDirectory(name, true))

    /** Only the four managed data folders use prefix matching; destination/subfolder names stay exact. */
    internal suspend fun backupDirOrNull(name: String): SafDir? {
        require(name in BACKUP_DIRECTORIES)
        return resolveDirectory(name, false, matchPrefix = true)
    }
    internal suspend fun backupDir(name: String): SafDir {
        require(name in BACKUP_DIRECTORIES)
        return requireNotNull(resolveDirectory(name, true, matchPrefix = true))
    }

    internal fun physicalDirectories(): List<SafDir> = folders.map { SafDir(session, listOf(it)) }

    /** Always create a new physical directory, including when archiving duplicate SAF folders. */
    internal suspend fun createDirectory(name: String): SafDir {
        requireSafeName(name)
        val made = provider.create(canonical, name, DIRECTORY_MIME)
        session.children(canonical).add(made)
        val actual = provider.metadata(made.id)
        if (!actual.directory || actual.name != name) throw BackupStorageException("The provider changed the archive folder name")
        return SafDir(session, listOf(actual))
    }

    /** Copy opaque bytes under their original name; no manifest parsing or decryption. */
    internal suspend fun copyFileTo(document: BackupDocument, target: SafDir) {
        require(!document.directory)
        val bytes = provider.read(document)
        val actual = target.createVerified(document.name, bytes, "application/octet-stream")
        if (actual.name != document.name) throw BackupStorageException("The provider changed an archived filename")
    }

    /** Native SAF move: no file-content reads, copies, or decryption. */
    internal suspend fun moveTo(document: BackupDocument, target: SafDir): BackupDocument {
        require(folders.size == 1 && target.folders.size == 1)
        // Forget the active ID before moving it: Drive can keep the same ID inside the archive.
        // Looking that ID up later must never reconnect a new backup to the archived folder.
        if (document.directory) {
            session.identities.remove(canonical.id, document.name)
            BACKUP_DIRECTORIES.firstOrNull { document.name.startsWith(it) }?.let {
                session.identities.remove(canonical.id, it)
            }
        }
        return try {
            provider.move(document, canonical, target.canonical)
        } finally {
            session.clear()
        }
    }

    private suspend fun resolveDirectory(name: String, create: Boolean, matchPrefix: Boolean = false): SafDir? {
        requireSafeName(name)
        fun matchesName(actual: String) = actual == name || matchPrefix && actual.startsWith(name)
        val entries = documents()
        val hint = session.identities.get(canonical.id, name)
        val matches = entries.filter { it.name == name || it.id == hint ||
            matchPrefix && it.directory && matchesName(it.name) }.toMutableList()
        if (matches.any { !it.directory }) throw BackupStorageException("A file conflicts with a backup folder")
        if (create && hint != null && matches.none { it.id == hint }) {
            try {
                val known = provider.metadata(hint)
                if (!known.directory || !matchesName(known.name)) throw BackupStorageException("The established backup folder changed")
                matches += known
            } catch (_: FileNotFoundException) {
                // Both a complete parent listing and a direct ID query confirm removal. Recreate
                // without deleting anything; a provider error/loading result never reaches here.
            }
        }
        val ordered = matches.sortedWith(compareBy<BackupDocument> { it.id != hint }
            .thenBy { it.name != name }.thenBy { it.id })
        val dirs = if (ordered.isNotEmpty()) ordered else {
            if (!create) return null
            val made = provider.create(canonical, name, DIRECTORY_MIME)
            session.identities.put(canonical.id, name, made.id)
            session.children(canonical).add(made)
            val verified = provider.metadata(made.id)
            if (!verified.directory || !matchesName(verified.name)) {
                throw BackupStorageException("The provider changed a backup folder name; choose another destination")
            }
            session.children(canonical).removeAll { it.id == made.id }
            session.children(canonical).add(verified)
            listOf(verified)
        }
        if (create) session.identities.put(canonical.id, name, dirs.first().id)
        return session.directories.getOrPut(dirs.map { it.id }) { SafDir(session, dirs) }
    }

    internal suspend fun read(document: BackupDocument): ByteArray = provider.read(document)

    internal suspend fun readVerified(name: String, expectedHash: String): ByteArray? {
        requireSafeName(name)
        var failure: Exception? = null
        var unavailable: Exception? = null
        for (doc in documents().filter { it.name == name && !it.directory }) {
            currentCoroutineContext().ensureActive()
            try {
                val bytes = provider.read(doc)
                if (backupHash(bytes) == expectedHash) {
                    session.verifiedHashes[doc.id] = expectedHash
                    return bytes
                }
                failure = BackupContentException("A backup file failed its integrity check")
            } catch (e: CancellationException) { throw e
            } catch (e: FileNotFoundException) { failure = BackupContentException("A backup file is missing", e)
            } catch (e: Exception) { unavailable = e }
        }
        if (unavailable != null) throw BackupStorageException("The backup provider could not read a file; retry later", unavailable)
        if (failure is BackupContentException) throw failure
        if (failure != null) throw BackupStorageException("No valid copy of a backup file is available", failure)
        return null
    }

    /** Reuse integrity checks already performed during this backup operation. */
    internal suspend fun verifyFile(name: String, expectedHash: String): Boolean {
        requireSafeName(name)
        if (documents().any { !it.directory && it.name == name && session.verifiedHashes[it.id] == expectedHash }) return true
        return readVerified(name, expectedHash) != null
    }

    /** Never truncate or delete a committed blob. Return the provider's actual filename. */
    internal suspend fun writeImmutable(name: String, bytes: ByteArray, mime: String = "application/octet-stream"): String {
        requireSafeName(name)
        val hash = backupHash(bytes)
        val existing = documents().filter { it.name == name }
        if (existing.any { it.directory }) throw BackupStorageException("A folder conflicts with a backup file")
        val reusable = try { verifyFile(name, hash) } catch (_: BackupContentException) { false }
        if (reusable) return name
        // A corrupt document may already occupy the content-addressed name. Give its replacement
        // a unique prefix so providers enforcing unique names do not invent an unrecognizable suffix.
        var desired = name
        var replacement = 0
        val usedNames = documents().map { it.name }.toSet()
        while (desired in usedNames) {
            desired = name.substringBefore('.') + ".repair${++replacement}." + name.substringAfter('.')
        }
        return createVerified(desired, bytes, mime).name
    }

    private suspend fun createVerified(name: String, bytes: ByteArray, mime: String): BackupDocument {
        val made = provider.create(canonical, name, mime)
        session.children(canonical).add(made)
        provider.write(made) { it.write(bytes) }
        val expectedHash = backupHash(bytes)
        val path = "${canonical.name}/$name"
        var lastFailure: IOException? = null
        // A cloud provider can finish processing a write after our stream closes. Reopen the same
        // document; retries must never create another copy or rewrite bytes that may already be saved.
        repeat(4) { attempt ->
            currentCoroutineContext().ensureActive()
            if (attempt > 0) delay(1_000L shl (attempt - 1))
            try {
                val actual = provider.metadata(made.id)
                if (actual.directory) throw BackupStorageException("The provider returned a folder for the written file")
                val readBack = provider.read(actual)
                if (backupHash(readBack) == expectedHash) {
                    session.children(canonical).removeAll { it.id == made.id }
                    session.children(canonical).add(actual)
                    session.verifiedHashes[actual.id] = expectedHash
                    if (attempt > 0) session.log("Write verification: verified $path on attempt ${attempt + 1}/4")
                    return actual
                }
                lastFailure = BackupStorageException("The bytes read back did not match the written file")
                session.log("Write verification: $path attempt ${attempt + 1}/4 mismatch; " +
                    "expectedBytes=${bytes.size} actualBytes=${readBack.size} " +
                    "expectedSHA256=${backupHashHex(bytes)} actualSHA256=${backupHashHex(readBack)}")
            } catch (e: IOException) {
                lastFailure = e
                session.log("Write verification: $path attempt ${attempt + 1}/4 unavailable; " +
                    "expectedBytes=${bytes.size} expectedSHA256=${backupHashHex(bytes)}; ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw BackupStorageException("Could not verify the saved backup file after 4 attempts", lastFailure)
    }

    /** Mutable GPX exports reuse an existing ID and surface failed duplicate deletion. */
    suspend fun writeFile(name: String, mime: String, write: (OutputStream) -> Unit) {
        requireSafeName(name)
        val files = documents().filter { it.name == name }
        if (files.any { it.directory }) throw BackupStorageException("A folder conflicts with the export file")
        val doc = files.firstOrNull() ?: provider.create(canonical, name, mime).also { session.children(canonical).add(it) }
        provider.write(doc, write)
        files.drop(1).forEach { delete(it) }
    }

    suspend fun deleteFile(name: String) {
        val files = documents().filter { it.name == name }
        if (files.any { it.directory }) throw BackupStorageException("Refusing to delete a backup folder as a file")
        files.forEach { delete(it) }
    }

    internal suspend fun delete(document: BackupDocument, path: String = document.name, log: (String) -> Unit = {},
        operation: String = "Cleanup") {
        val parent = if ('/' in path) folders.firstOrNull { folder ->
            session.children(folder).any { it.id == document.id }
        } else null
        val actualPath = parent?.let { "${it.name}/${path.substringAfter('/')}" } ?: path
        deleteDocument(document, actualPath, log, operation)
        session.verifiedHashes.remove(document.id)
        folders.forEach { session.children(it).removeAll { child -> child.id == document.id } }
    }

    private suspend fun deleteDocument(document: BackupDocument, path: String, log: (String) -> Unit,
        operation: String = "Cleanup") {
        log("$operation: deleting $path")
        try {
            provider.delete(document)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            log("$operation: failed to delete $path: ${e.message ?: e.javaClass.simpleName}")
            throw e
        }
        log("$operation: deleted $path")
    }

    internal suspend fun refresh() { folders.forEach { session.invalidate(it); session.children(it) } }

    /** Consolidate verified retained objects before removing identical copies from other folders. */
    internal suspend fun consolidate(name: String, hash: String, path: String = name, log: (String) -> Unit = {}) {
        val candidates = documents().filter { !it.directory && it.name == name }
        val canonicalIds = session.children(canonical).map { it.id }.toSet()
        // Ordinary retained files need no content scan. Only duplicate resolution or copying a
        // file out of a duplicate folder needs a verified keeper before deleting anything.
        if (candidates.isEmpty() || candidates.size == 1 && candidates.single().id in canonicalIds) return
        var keeper = candidates.firstOrNull { it.id in canonicalIds && session.verifiedHashes[it.id] == hash }
        var bytes: ByteArray? = null
        for (doc in if (keeper == null) candidates else emptyList()) {
            val raw = try { provider.read(doc) } catch (_: FileNotFoundException) { continue }
            if (backupHash(raw) == hash) {
                bytes = raw
                if (doc.id in canonicalIds) { keeper = doc; break }
            }
        }
        if (keeper == null) {
            if (bytes == null) throw BackupContentException("No valid copy of a current backup file is available")
            keeper = createVerified(name, bytes, "application/octet-stream")
            // The committed inventory refers to the exact display name. Keep its original if renamed.
            if (keeper.name != name) return
            log("Cleanup: copied $path to the primary folder")
        }
        for (doc in candidates) {
            // The committed inventory identifies one expected hash. Once its canonical copy is
            // verified, all other documents with this name are redundant, including corrupt copies.
            if (doc.id != keeper.id) delete(doc, "$path (duplicate)", log)
        }
    }

    internal suspend fun removeEmptyDuplicateFolders(log: (String) -> Unit = {}) {
        for (folder in folders.drop(1)) {
            session.invalidate(folder)
            if (session.children(folder).isEmpty()) deleteDocument(folder, "${folder.name}/ (empty duplicate folder)", log)
        }
    }

    private fun requireSafeName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "Invalid backup document name" }
    }
    internal companion object {
        const val DIRECTORY_MIME = "vnd.android.document/directory"
        val BACKUP_DIRECTORIES = setOf("samples", "visits", "trips", "snapshot")
    }
}
