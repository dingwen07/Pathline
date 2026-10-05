package net.extrawdw.apps.locationhistory.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.security.BackupCrypto
import net.extrawdw.apps.locationhistory.security.CryptoHeader
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One writer per destination. A numbered manifest publishes immutable data before cleanup. */
internal object BackupArchive {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val generationName = Regex("manifest\\.([1-9]\\d*)\\.json")
    private const val MANIFEST_NAME = "manifest.json"
    private val blobName = Regex(".+\\.[a-f0-9]{16,64}\\.(?:jsonl\\.gz|json\\.gz|gz)(?:\\.enc)?")
    private val providerCopySuffix = Regex(" \\([1-9]\\d*\\)")
    private val streams = setOf("samples", "trips", "visits")

    data class Record(val document: BackupDocument, val manifest: BackupManifest)
    data class Scan(val records: List<Record>, val invalid: List<BackupDocument>, val highestSequence: Long)
    data class Opened(val manifest: BackupManifest, val inventory: BackupInventory)
    data class Recovery(val opened: Opened, val cipher: BackupCrypto.PartitionCipher, val recovered: Boolean)
    data class Blob(val fileName: String, val plainHash: String, val diskHash: String)
    data class Cleanup(val complete: Boolean, val detail: String? = null, val error: Exception? = null)

    private fun isManifest(name: String) = name.startsWith("manifest") && name.endsWith(".json")
    private fun isBlob(name: String) = blobName.matches(name.replace(providerCopySuffix, ""))
    private fun isInventory(name: String) = name.startsWith("inventory.") && isBlob(name)

    suspend fun hasBackupFiles(root: SafDir): Boolean {
        if (root.documents().any { !it.directory && (isManifest(it.name) || isInventory(it.name)) }) return true
        for (name in streams + "snapshot") {
            if (root.backupDirOrNull(name)?.documents()?.any { !it.directory && isBlob(it.name) } == true) return true
        }
        return false
    }

    /** Encryption changes discard active backup files by name; old contents need no credentials. */
    suspend fun deleteBeforeEncryptionChange(root: SafDir, log: (String) -> Unit = {}) {
        root.refresh()
        val groups = mutableListOf(root to root.documents().filter {
            !it.directory && (isManifest(it.name) || isInventory(it.name))
        }.sortedBy { !isManifest(it.name) })
        // Finish listing before deleting anything, so a failed listing cannot clear half the backup.
        for (name in streams + "snapshot") {
            val dir = root.backupDirOrNull(name) ?: continue
            dir.refresh()
            groups += dir to dir.documents().filter { !it.directory && isBlob(it.name) }
        }
        log("Encryption change: deleting ${groups.sumOf { it.second.size }} existing backup file(s) before writing the replacement")
        for ((dir, files) in groups) {
            val prefix = if (dir === root) "" else "${dir.canonical.name}/"
            for (doc in files) {
                currentCoroutineContext().ensureActive()
                dir.delete(doc, prefix + doc.name, log, operation = "Encryption change")
            }
        }
        log("Encryption change: existing backup files deleted; new backup sequence starts at 1")
    }

    /** Select by filename only, preserving even damaged or differently encrypted backup bytes. */
    suspend fun archiveBeforeFull(root: SafDir, nowMs: Long, log: (String) -> Unit = {}): String? {
        data class Group(val source: SafDir, val folderName: String?, val files: List<BackupDocument>)
        root.refresh()
        val groups = mutableListOf<Group>()
        val rootFiles = root.documents().filter { !it.directory && (isManifest(it.name) || isInventory(it.name)) }
        if (rootFiles.isNotEmpty()) groups += Group(root, null, rootFiles)
        for (name in streams + "snapshot") {
            for (dir in root.backupDirOrNull(name)?.physicalDirectories().orEmpty()) {
                dir.refresh()
                val files = dir.documents().filter { !it.directory && isBlob(it.name) }
                if (files.isNotEmpty()) groups += Group(dir, dir.canonical.name, files)
            }
        }
        if (groups.isEmpty()) return null
        val managedFolders = groups.filter { it.folderName != null }
        val moveIds = (rootFiles + managedFolders.map { it.source.canonical }).map { it.id }.toSet()
        val moves = root.physicalDirectories().flatMap { parent ->
            parent.documents().filter { it.id in moveIds }.map { parent to it }
        }.sortedBy { (_, doc) -> if (doc.directory) 0 else if (isManifest(doc.name)) 2 else 1 }
        // A whole-folder move is safe only when everything inside belongs to Pathline. Otherwise
        // keep the filename-based copy path so unrelated contents stay in their original location.
        val canMove = moves.all { it.second.supportsMove } &&
            managedFolders.all { it.files.size == it.source.documents().size }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(nowMs))
        val base = "archive-$stamp"
        val used = root.documents().map { it.name }.toSet()
        var name = base
        var suffix = 1
        while (name in used) name = "$base-${suffix++}"
        log("Archive: preserving existing backup files in $name/")
        try {
            val archive = root.createDirectory(name)
            if (canMove) {
                moveIntoArchive(moves, archive, name, log)
                log("Archive: complete in $name/; new backup sequence starts at 1")
                return name
            }
            log("Archive: using file copies because native moves are unavailable or a managed folder contains unrelated entries")
            // Finish every copy before removing any original. A failed copy leaves the original
            // backup intact; a failed deletion leaves a complete archive available for restore.
            for (group in groups) {
                val target = group.folderName?.let { archive.createDirectory(it) } ?: archive
                for (doc in group.files) {
                    val path = (group.folderName?.let { "$it/" } ?: "") + doc.name
                    log("Archive: copying $path to $name/$path")
                    group.source.copyFileTo(doc, target)
                    log("Archive: copied $path to $name/$path")
                }
            }
            log("Archive: all copies verified in $name/; removing originals")
            for (group in groups) {
                for (doc in group.files.sortedBy { !isManifest(it.name) }) {
                    val path = (group.folderName?.let { "$it/" } ?: "") + doc.name
                    group.source.delete(doc, path, log, operation = "Archive")
                }
            }
            log("Archive: complete in $name/; new backup sequence starts at 1")
            return name
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            val message = "Could not archive existing backup files in $name/: ${e.message ?: e.javaClass.simpleName}"
            log("Archive: $message")
            throw BackupStorageException(message, e)
        }
    }

    private suspend fun moveIntoArchive(items: List<Pair<SafDir, BackupDocument>>, archive: SafDir,
        name: String, log: (String) -> Unit) {
        val moved = mutableListOf<Pair<SafDir, BackupDocument>>()
        try {
            for ((source, document) in items) {
                currentCoroutineContext().ensureActive()
                val path = document.name + if (document.directory) "/" else ""
                log("Archive: moving $path to $name/$path")
                // Record an in-flight move even if cancellation arrives before its result returns.
                withContext(NonCancellable) { moved += source to source.moveTo(document, archive) }
                log("Archive: moved $path to $name/$path")
            }
        } catch (e: Exception) {
            // Keep a failed attempt from splitting the old backup between the root and archive.
            withContext(NonCancellable) {
                for ((source, document) in moved.asReversed()) {
                    try {
                        archive.moveTo(document, source)
                        log("Archive: returned ${document.name} to its original folder")
                    } catch (rollback: Exception) {
                        log("Archive: could not return ${document.name}; it remains in $name/: ${rollback.message}")
                        e.addSuppressed(rollback)
                    }
                }
            }
            throw e
        }
    }

    /** [previous] must come from an inventory opened with the current encryption context. */
    suspend fun storeBlob(dir: SafDir, base: String, extension: String, plain: ByteArray,
        cipher: BackupCrypto.PartitionCipher, previous: Blob?): Blob {
        val hash = backupHash(plain)
        if (previous != null && previous.plainHash == hash) {
            val valid = try { dir.verifyFile(previous.fileName, previous.diskHash) }
                catch (_: BackupContentException) { false }
            if (valid) return previous
        }
        val disk = encodeBlob(plain, cipher)
        val desired = "$base.${backupHashHex(disk)}.$extension" + if (cipher.encrypted) ".enc" else ""
        return Blob(dir.writeImmutable(desired, disk), hash, backupHash(disk))
    }

    fun checksum(manifest: BackupManifest): String {
        val encoded = json.encodeToJsonElement(BackupManifest.serializer(), manifest.copy(checksum = "")).jsonObject
        // v1 checksums cover the original PASSKEY enum and passkey key, including its null default.
        // Reading a legacy backup must not change those canonical bytes during the WebAuthn rename.
        val canonical = if (manifest.formatVersion == 1) {
            val crypto = encoded.getValue("crypto").jsonObject
            val legacyCrypto = JsonObject(crypto.entries.associate { (key, value) ->
                (if (key == "webauthn") "passkey" else key) to
                    if (key == "mode" && value == JsonPrimitive("WEBAUTHN")) JsonPrimitive("PASSKEY") else value
            })
            JsonObject(encoded.toMutableMap().also { it["crypto"] = legacyCrypto })
        } else encoded
        return backupHash(json.encodeToString(JsonObject.serializer(), canonical).toByteArray())
    }

    suspend fun scan(root: SafDir): Scan {
        val records = mutableListOf<Record>()
        val invalid = mutableListOf<BackupDocument>()
        var sequence = 0L
        for (doc in root.documents().filter { !it.directory }) {
            val originalName = doc.name.replace(providerCopySuffix, "")
            val named = generationName.matchEntire(originalName)
            if (originalName != MANIFEST_NAME && named == null) continue
            named?.groupValues?.get(1)?.toLongOrNull()?.let { sequence = maxOf(sequence, it) }
            // Unavailable reads are errors. Only bytes actually read and found incomplete are skipped.
            val bytes = root.read(doc)
            val manifest = try { json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString()) }
                catch (_: kotlinx.serialization.SerializationException) { null }
                catch (_: IllegalArgumentException) { null }
            if (manifest == null || manifest.checksum != checksum(manifest)) { invalid += doc; continue }
            require(manifest.formatVersion <= Constants.BACKUP_FORMAT_VERSION) { "Backup format is newer than this app; update Pathline" }
            val gen = manifest.generation
            if (manifest.formatVersion == 2) {
                if (gen == null || gen.sequence <= 0 || named == null || gen.sequence != named.groupValues[1].toLongOrNull()) {
                    invalid += doc; continue
                }
                sequence = maxOf(sequence, gen.sequence)
            } else if (manifest.formatVersion != 1 || gen != null || originalName != MANIFEST_NAME) {
                invalid += doc; continue
            }
            records += Record(doc, manifest)
        }
        val ordered = records.sortedWith(compareByDescending<Record> { it.manifest.generation?.sequence ?: 0L }
            .thenByDescending { it.manifest.createdAtMs }.thenBy { it.document.id })
        val generated = ordered.filter { it.manifest.generation != null }.groupBy { it.manifest.generation!!.sequence }
        require(generated.values.none { group -> group.map { it.manifest.checksum }.distinct().size > 1 }) {
            "Conflicting backup generations; use a dedicated backup folder for this installation"
        }
        return Scan(ordered, invalid, sequence)
    }

    suspend fun latest(root: SafDir): BackupManifest? {
        return scan(root).records.firstOrNull()?.manifest
    }

    /** Restore searches complete generations before changing any local data. Provider errors still retry. */
    suspend fun recover(root: SafDir, password: CharArray?, prfSecret: ByteArray?, maxSchemaVersion: Int): Recovery {
        val candidates = scan(root).records.distinctBy { it.manifest.checksum }
        var lastContentError: BackupContentException? = null
        for ((index, candidate) in candidates.withIndex()) {
            val manifest = candidate.manifest
            require(manifest.schemaVersion <= maxSchemaVersion) { "Backup schema is newer than this app; update Pathline" }
            // Wrong credentials must be surfaced, not mistaken for damaged backup contents.
            val cipher = BackupCrypto.PartitionCipher(BackupCrypto.openDek(manifest.crypto, password, prfSecret))
            try {
                val inventory = inventory(root, manifest, cipher)
                validate(root, inventory, cipher)
                return Recovery(Opened(manifest, inventory), cipher, index > 0)
            } catch (e: BackupContentException) { lastContentError = e }
        }
        throw BackupContentException("No complete backup generation could be recovered; existing files were preserved", lastContentError)
    }

    fun encodeBlob(bytes: ByteArray, cipher: BackupCrypto.PartitionCipher): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(cipher.wrap(out)).use { it.write(bytes) } }.toByteArray()

    fun decodeBlob(bytes: ByteArray, cipher: BackupCrypto.PartitionCipher): ByteArray =
        GZIPInputStream(cipher.unwrap(bytes.inputStream())).use { it.readBytes() }

    suspend fun inventory(root: SafDir, manifest: BackupManifest, cipher: BackupCrypto.PartitionCipher): BackupInventory {
        val bytes = root.readVerified(manifest.inventory.fileName, manifest.inventory.sha256)
            ?: throw BackupContentException("Missing backup inventory")
        val inventory = try {
            json.decodeFromString(BackupInventory.serializer(), decodeBlob(bytes, cipher).decodeToString())
        } catch (e: Exception) { throw BackupContentException("Backup inventory contents are damaged", e) }
        if (inventory.partitions.any { it.stream !in streams } ||
            inventory.partitions.map { it.stream to it.weekStart }.distinct().size != inventory.partitions.size ||
            inventory.snapshots.map { it.name }.distinct().size != inventory.snapshots.size) {
            throw BackupContentException("Backup inventory contains invalid or duplicate entries")
        }
        return inventory
    }

    suspend fun validate(root: SafDir, inventory: BackupInventory, cipher: BackupCrypto.PartitionCipher) {
        for (entry in inventory.partitions) {
            validateBlob(root.backupDirOrNull(entry.stream), entry.fileName, entry.encSha256, entry.sha256, cipher)
        }
        val snapshots = if (inventory.snapshots.isEmpty()) null else root.backupDirOrNull("snapshot")
        for (entry in inventory.snapshots) validateBlob(snapshots, entry.fileName, entry.encSha256, entry.sha256, cipher)
    }

    /** A missing/corrupt weekly file only requires re-exporting that partition from the database. */
    suspend fun partitionsToRepair(root: SafDir, entries: List<PartitionEntry>): List<PartitionEntry> =
        entries.filter { entry ->
            try {
                root.backupDirOrNull(entry.stream)?.verifyFile(entry.fileName, entry.encSha256) != true
            } catch (_: BackupContentException) { true }
        }

    /** Publication reuses file checks from this operation; restore separately verifies plaintext. */
    private suspend fun verifyFiles(root: SafDir, inventory: BackupInventory) {
        for (entry in inventory.partitions) {
            if (root.backupDirOrNull(entry.stream)?.verifyFile(entry.fileName, entry.encSha256) != true) {
                throw BackupContentException("A referenced backup partition is unavailable")
            }
        }
        val snapshots = if (inventory.snapshots.isEmpty()) null else root.backupDirOrNull("snapshot")
        for (entry in inventory.snapshots) {
            if (snapshots?.verifyFile(entry.fileName, entry.encSha256) != true) {
                throw BackupContentException("A referenced backup snapshot is unavailable")
            }
        }
    }

    private suspend fun validateBlob(dir: SafDir?, name: String, diskHash: String, plainHash: String, cipher: BackupCrypto.PartitionCipher) {
        val raw = dir?.readVerified(name, diskHash) ?: throw BackupContentException("A referenced backup file is unavailable")
        val plain = try { decodeBlob(raw, cipher) }
            catch (e: Exception) { throw BackupContentException("Backup contents could not be decoded", e) }
        if (backupHash(plain) != plainHash) throw BackupContentException("Backup contents failed integrity verification")
    }

    suspend fun publish(root: SafDir, header: CryptoHeader, cipher: BackupCrypto.PartitionCipher,
        inventory: BackupInventory, schemaVersion: Int, nowMs: Long, previous: Opened?): BackupManifest {
        verifyFiles(root, inventory)
        val plain = json.encodeToString(BackupInventory.serializer(), inventory).toByteArray()
        val old = previous?.takeIf { it.manifest.crypto == header && it.inventory == inventory }
        val reusableInventory = try {
            old != null && root.verifyFile(old.manifest.inventory.fileName, old.manifest.inventory.sha256)
        } catch (_: BackupContentException) { false }
        val ref = if (old != null && reusableInventory) {
            old.manifest.inventory
        } else {
            val disk = encodeBlob(plain, cipher)
            val desired = "inventory.${backupHashHex(disk)}.json.gz" + if (cipher.encrypted) ".enc" else ""
            InventoryRef(root.writeImmutable(desired, disk), backupHash(disk))
        }
        val scan = scan(root)
        require(scan.highestSequence < Long.MAX_VALUE) { "Backup generation counter exhausted" }
        val generation = BackupGeneration(sequence = scan.highestSequence + 1)
        val unsigned = BackupManifest(Constants.BACKUP_FORMAT_VERSION, schemaVersion, nowMs, header, ref, generation = generation)
        val manifest = unsigned.copy(checksum = checksum(unsigned))
        val name = manifestName(manifest)
        val bytes = json.encodeToString(BackupManifest.serializer(), manifest).toByteArray()
        val actual = root.writeImmutable(name, bytes, "application/json")
        require(actual == name) { "The provider changed the manifest filename" }
        return manifest
    }

    private fun manifestName(manifest: BackupManifest) = "manifest.${requireNotNull(manifest.generation).sequence}.json"

    /** The just-published inventory is the entire keep-set; old backups need no keys or validation. */
    suspend fun cleanup(root: SafDir, committed: Opened, log: (String) -> Unit = {}): Cleanup {
        try {
            log("Cleanup: keeping ${manifestName(committed.manifest)} and its referenced files")
            root.refresh()
            val manifest = committed.manifest
            val inventory = committed.inventory
            val name = manifestName(manifest)
            val hash = backupHash(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            if (!root.verifyFile(name, hash)) {
                return Cleanup(false, "Published manifest is unavailable; cleanup will retry")
            }
            root.consolidate(name, hash, log = log)
            // Retire old commit records before their data. An interrupted cleanup leaves the new
            // manifest readable and harmless obsolete files for the next backup to remove.
            for (doc in root.documents()) {
                if (!doc.directory && doc.name != name &&
                    isManifest(doc.name)) root.delete(doc, log = log)
            }
            cleanFiles(root, listOf(manifest.inventory.fileName to manifest.inventory.sha256), "", inventoriesOnly = true, log = log)
            for (stream in streams + "snapshot") {
                val dir = root.backupDirOrNull(stream) ?: continue
                dir.refresh()
                val refs = if (stream == "snapshot") inventory.snapshots.map { it.fileName to it.encSha256 }
                    else inventory.partitions.filter { it.stream == stream }.map { it.fileName to it.encSha256 }
                cleanFiles(dir, refs, "$stream/", inventoriesOnly = false, log = log)
                dir.removeEmptyDuplicateFolders(log)
            }
            log("Cleanup: complete")
            return Cleanup(true)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            return Cleanup(false, "Cleanup will retry: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private suspend fun cleanFiles(dir: SafDir, refs: List<Pair<String, String>>, prefix: String,
        inventoriesOnly: Boolean, log: (String) -> Unit) {
        val keep = refs.groupBy({ it.first }, { it.second })
        for ((name, hashes) in keep) if (hashes.distinct().size == 1) dir.consolidate(name, hashes.first(), prefix + name, log)
        for (doc in dir.documents()) {
            if (!doc.directory && doc.name !in keep && isBlob(doc.name) &&
                (!inventoriesOnly || doc.name.startsWith("inventory."))) dir.delete(doc, prefix + doc.name, log)
        }
    }
}
