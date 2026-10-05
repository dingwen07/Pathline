package net.extrawdw.apps.locationhistory.backup

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.extrawdw.apps.locationhistory.security.BackupCrypto
import net.extrawdw.apps.locationhistory.security.BackupEncryption
import net.extrawdw.apps.locationhistory.security.CryptoHeader
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

internal class MemoryDocuments : BackupDocuments {
    data class Node(val doc: BackupDocument, var parent: String?, var bytes: ByteArray = byteArrayOf())
    val root = BackupDocument("root", "root", true)
    val nodes = linkedMapOf(root.id to Node(root, null))
    var clock = 10 * 86_400_000L
    var next = 0
    var listError: Exception? = null
    var writeErrorFor: String? = null
    var corruptWrites = false
    var supportsMoves = false
    var changeIdsOnMove = false
    val moveErrors = mutableSetOf<String>()
    val moved = mutableListOf<String>()
    val deleteErrors = mutableSetOf<String>()
    val readErrors = mutableSetOf<String>()
    val deleted = mutableListOf<String>()
    val reads = mutableListOf<String>()
    val writes = mutableListOf<String>()
    var readResult: ((BackupDocument, ByteArray) -> ByteArray)? = null
    override suspend fun list(parent: BackupDocument): List<BackupDocument> {
        listError?.let { throw it }
        return nodes.values.filter { it.parent == parent.id }.map { it.doc }
    }
    override suspend fun metadata(id: String): BackupDocument = nodes[id]?.doc ?: throw FileNotFoundException()
    override suspend fun create(parent: BackupDocument, name: String, mime: String): BackupDocument =
        add(parent, name, mime == SafDir.DIRECTORY_MIME)
    fun add(parent: BackupDocument, name: String, directory: Boolean = false, bytes: ByteArray = byteArrayOf()): BackupDocument {
        val doc = BackupDocument("doc-${++next}", name, directory, clock, supportsMove = supportsMoves)
        nodes[doc.id] = Node(doc, parent.id, bytes)
        return doc
    }
    override suspend fun read(document: BackupDocument): ByteArray {
        reads += document.id
        if (document.id in readErrors) throw IOException("provider unavailable")
        val bytes = nodes[document.id]?.bytes ?: throw FileNotFoundException()
        return readResult?.invoke(document, bytes) ?: bytes
    }
    override suspend fun write(document: BackupDocument, block: (OutputStream) -> Unit) {
        writes += document.id
        if (writeErrorFor?.let { document.name.startsWith(it) } == true) {
            nodes.getValue(document.id).bytes = "{".toByteArray()
            throw IOException("interrupted write")
        }
        nodes.getValue(document.id).bytes = if (corruptWrites) "bad copy".toByteArray() else ByteArrayOutputStream().also(block).toByteArray()
    }
    override suspend fun delete(document: BackupDocument) {
        if (document.id in deleteErrors) throw IOException("delete refused")
        nodes.remove(document.id)
        deleted += document.id
    }
    override suspend fun move(document: BackupDocument, sourceParent: BackupDocument, targetParent: BackupDocument): BackupDocument {
        check(document.supportsMove)
        if (document.id in moveErrors) throw IOException("move refused")
        val node = nodes.getValue(document.id)
        check(node.parent == sourceParent.id)
        val result = if (changeIdsOnMove) document.copy(id = "doc-${++next}") else document
        nodes.remove(document.id)
        nodes[result.id] = Node(result, targetParent.id, node.bytes)
        nodes.values.filter { it.parent == document.id }.forEach { it.parent = result.id }
        moved += document.name
        return result
    }
    fun dir() = SafDir(this, root)
    fun named(name: String) = nodes.values.filter { it.doc.name == name }
}

class BackupArchiveTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val plainHeader = BackupCrypto.plaintextHeader()
    private val plainCipher = BackupCrypto.PartitionCipher(null)

    @Test fun delayedReadAfterWriteVerifiesTheSameEncryptedFileWithoutRewriting() = runBlocking {
        val provider = MemoryDocuments()
        var delayedReads = 2
        provider.readResult = { _, bytes ->
            when (delayedReads--) {
                2 -> byteArrayOf()
                1 -> bytes.copyOf(bytes.size - 1)
                else -> bytes
            }
        }
        val logs = mutableListOf<String>()
        val root = SafDir(provider, provider.root, log = logs::add)
        val cipher = BackupCrypto.PartitionCipher(ByteArray(32) { 7 })
        val plain = "trips for W40".toByteArray()
        val blob = BackupArchive.storeBlob(root, "2026-W40", "jsonl.gz", plain, cipher, null)
        val file = provider.named(blob.fileName).single()

        assertEquals(listOf(file.doc.id), provider.writes)
        assertEquals(listOf(file.doc.id, file.doc.id, file.doc.id), provider.reads)
        assertEquals(blob.diskHash, backupHash(file.bytes))
        assertArrayEquals(plain, BackupArchive.decodeBlob(file.bytes, cipher))
        assertEquals(2, logs.count { it.contains("mismatch") })
        assertTrue(logs.any { it.contains("actualBytes=0") && it.contains("expectedBytes=${file.bytes.size}") })
        assertTrue(logs.any { it.contains("expectedSHA256=${backupHashHex(file.bytes)}") })
        assertTrue(logs.last().contains("verified"))
        // A successful check is cached for this session; later publication need not read it again.
        assertTrue(root.verifyFile(blob.fileName, blob.diskHash))
        assertEquals(3, provider.reads.size)
        assertTrue(provider.deleted.isEmpty())
    }

    @Test fun temporarilyUnavailableWrittenFileCanBeVerifiedOnRetry() = runBlocking {
        val provider = MemoryDocuments()
        var unavailableReads = 2
        provider.readResult = { _, bytes ->
            when (unavailableReads--) {
                2 -> throw FileNotFoundException("file not visible yet")
                1 -> throw IOException("provider still processing")
                else -> bytes
            }
        }
        val logs = mutableListOf<String>()
        val root = SafDir(provider, provider.root, log = logs::add)
        val bytes = "complete file".toByteArray()
        root.writeImmutable("2026-W40.hash.jsonl.gz", bytes)

        assertEquals(1, provider.writes.size)
        assertEquals(3, provider.reads.size)
        assertEquals(setOf(provider.writes.single()), provider.reads.toSet())
        assertEquals(2, logs.count { it.contains("unavailable") })
        assertTrue(logs.last().contains("verified"))
    }

    @Test fun persistentWriteCorruptionFailsAfterBoundedChecksWithoutRewritingOrCachingSuccess() = runBlocking {
        val provider = MemoryDocuments().apply { corruptWrites = true }
        val logs = mutableListOf<String>()
        val root = SafDir(provider, provider.root, log = logs::add)
        val bytes = "complete file".toByteArray()
        val name = "2026-W40.hash.jsonl.gz"

        val error = runCatching { root.writeImmutable(name, bytes) }.exceptionOrNull()
        assertTrue(error is BackupStorageException)
        assertTrue(error!!.message!!.contains("Could not verify"))
        assertEquals(4, provider.reads.size)
        assertEquals(1, provider.writes.size)
        assertEquals(1, provider.named(name).size)
        assertEquals(setOf(provider.writes.single()), provider.reads.toSet())
        assertEquals(4, logs.count { it.contains("mismatch") })
        assertFalse(logs.any { it.contains("verified") })
        assertTrue(runCatching { root.verifyFile(name, backupHash(bytes)) }.exceptionOrNull() is BackupContentException)
        assertTrue(provider.deleted.isEmpty())
    }

    @Test fun cancellingVerificationBackoffStopsWithoutAnotherReadOrWrite() = runBlocking {
        val provider = MemoryDocuments().apply { readResult = { _, _ -> byteArrayOf() } }
        val logs = mutableListOf<String>()
        val root = SafDir(provider, provider.root, log = logs::add)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            root.writeImmutable("2026-W40.hash.jsonl.gz", "complete file".toByteArray())
        }
        // The initial read has failed and the coroutine is suspended in its retry delay.
        assertEquals(1, provider.reads.size)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, provider.reads.size)
        assertEquals(1, provider.writes.size)
        assertFalse(logs.any { it.contains("verified") })
        assertTrue(provider.deleted.isEmpty())
    }

    private suspend fun snapshot(provider: MemoryDocuments, text: String, previous: BackupArchive.Opened? = null,
        header: CryptoHeader = plainHeader, cipher: BackupCrypto.PartitionCipher = plainCipher): BackupArchive.Opened {
        val root = provider.dir()
        val old = previous?.takeIf { it.manifest.crypto == header }?.inventory?.snapshots?.singleOrNull()
        val blob = BackupArchive.storeBlob(root.childDir("snapshot"), "settings", "gz", text.toByteArray(), cipher,
            old?.let { BackupArchive.Blob(it.fileName, it.sha256, it.encSha256) })
        val inventory = BackupInventory(snapshots = listOf(SnapshotEntry("settings", blob.fileName, 1, blob.plainHash, blob.diskHash)))
        val manifest = BackupArchive.publish(root, header, cipher, inventory, 1, provider.clock, previous)
        return BackupArchive.Opened(manifest, inventory)
    }

    @Test fun repeatedV2DumpAndFolderCheck() = runBlocking {
        val provider = MemoryDocuments()
        val first = snapshot(provider, "settings")
        assertEquals(first.manifest, BackupArchive.latest(provider.dir()))
        val second = snapshot(provider, "settings", first)
        assertEquals(second.manifest, BackupArchive.latest(provider.dir()))
        assertEquals(first.manifest.inventory, second.manifest.inventory)
        assertEquals(listOf("manifest.1.json", "manifest.2.json"), provider.nodes.values.map { it.doc.name }.filter { it.startsWith("manifest.") })
        val wire = json.encodeToString(BackupManifest.serializer(), second.manifest)
        assertTrue(wire.contains("\"generation\":{\"sequence\":2}"))
        assertFalse(wire.contains("parentId"))
    }

    @Test fun legacyDumpThenV2ThenAnotherDump() = runBlocking {
        val provider = MemoryDocuments()
        val invBytes = BackupArchive.encodeBlob("{\"partitions\":[],\"snapshots\":[]}".toByteArray(), plainCipher)
        provider.add(provider.root, "inventory.legacy.json.gz", bytes = invBytes)
        val old = """{"formatVersion":1,"schemaVersion":1,"createdAtMs":1,"crypto":{"version":1,"mode":"NONE","password":null,"passkey":null},"inventory":{"fileName":"inventory.legacy.json.gz","sha256":"${backupHash(invBytes)}"},"checksum":""}"""
        val hash = backupHash(old.toByteArray())
        provider.add(provider.root, "manifest.json", bytes = old.replace("\"checksum\":\"\"", "\"checksum\":\"$hash\"").toByteArray())
        assertNotNull(BackupArchive.latest(provider.dir()))
        val first = snapshot(provider, "settings")
        assertEquals(first.manifest, BackupArchive.latest(provider.dir()))
        val second = snapshot(provider, "settings", first)
        assertEquals(second.manifest, BackupArchive.latest(provider.dir()))
    }

    @Test fun incompleteNewestAutomaticallyRecoversPrevious() = runBlocking {
        val provider = MemoryDocuments()
        val first = snapshot(provider, "old")
        val newest = snapshot(provider, "new", first)
        provider.named(newest.inventory.snapshots.single().fileName).forEach { provider.nodes.remove(it.doc.id) }
        val recovery = BackupArchive.recover(provider.dir(), null, null, 1)
        assertTrue(recovery.recovered)
        assertEquals(first.manifest, recovery.opened.manifest)
        assertTrue(provider.deleted.isEmpty())
    }

    @Test fun sequenceOrderingIsNumericAndCleanupLeavesOnlyTheLatestManifest() = runBlocking {
        val provider = MemoryDocuments()
        var next = snapshot(provider, "settings")
        repeat(9) { next = snapshot(provider, "settings", next) }
        assertEquals(10L, next.manifest.generation!!.sequence)
        assertTrue(provider.named("manifest.10.json").isNotEmpty())
        assertEquals(next.manifest, BackupArchive.latest(provider.dir()))
        assertTrue(BackupArchive.cleanup(provider.dir(), next).complete)
        assertEquals(listOf("manifest.10.json"), provider.nodes.values.map { it.doc.name }.filter { it.startsWith("manifest.") })
    }

    @Test fun missingSnapshotIsRebuiltFromLocalBytes() = runBlocking {
        val provider = MemoryDocuments()
        val first = snapshot(provider, "settings")
        provider.named(first.inventory.snapshots.single().fileName).forEach { provider.nodes.remove(it.doc.id) }
        val repaired = snapshot(provider, "settings", first)
        assertEquals(repaired.manifest, BackupArchive.recover(provider.dir(), null, null, 1).opened.manifest)
    }

    @Test fun interruptedManifestPreservesPrevious() = runBlocking {
        val provider = MemoryDocuments()
        val first = snapshot(provider, "old")
        provider.writeErrorFor = "manifest."
        assertTrue(runCatching { snapshot(provider, "new", first) }.isFailure)
        assertEquals(first.manifest, BackupArchive.recover(provider.dir(), null, null, 1).opened.manifest)
    }

    @Test fun encryptedUnchangedContentReusesCiphertextAndInventory() = runBlocking {
        val provider = MemoryDocuments()
        val (header, dek) = BackupCrypto.createWebAuthnHeader(ByteArray(32) { 7 }, ByteArray(32) { 8 }, "credential")
        val cipher = BackupCrypto.PartitionCipher(dek)
        val first = snapshot(provider, "settings", header = header, cipher = cipher)
        val second = snapshot(provider, "settings", first, header, cipher)
        assertEquals(first.inventory, second.inventory)
        assertEquals(first.manifest.inventory, second.manifest.inventory)
        val wire = json.encodeToString(BackupManifest.serializer(), second.manifest)
        assertTrue(wire.contains("\"mode\":\"WEBAUTHN\""))
        assertTrue(wire.contains("\"webauthn\":"))
        assertFalse(wire.contains("PASSKEY"))
        assertFalse(wire.contains("\"passkey\":"))
        assertEquals(BackupEncryption.WEBAUTHN, BackupEncryption.fromStored("PASSKEY"))
    }

    @Test fun loadingOrProviderFailureNeverCreatesOrDeletes() = runBlocking {
        val provider = MemoryDocuments()
        provider.listError = IOException("still loading")
        assertTrue(runCatching { provider.dir().childDir("snapshot") }.isFailure)
        assertEquals(1, provider.nodes.size)
        assertTrue(provider.deleted.isEmpty())
    }

    @Test fun duplicateFoldersResolveByHashAndConsolidate() = runBlocking {
        val provider = MemoryDocuments()
        val a = provider.add(provider.root, "snapshot", true)
        val b = provider.add(provider.root, "snapshot", true)
        provider.add(a, "settings.hash.gz", bytes = "bad".toByteArray())
        provider.add(b, "settings.hash.gz", bytes = "good".toByteArray())
        val dir = provider.dir().childDir("snapshot")
        assertArrayEquals("good".toByteArray(), dir.readVerified("settings.hash.gz", backupHash("good".toByteArray())))
        val logs = mutableListOf<String>()
        dir.consolidate("settings.hash.gz", backupHash("good".toByteArray()), "snapshot/settings.hash.gz", logs::add)
        dir.removeEmptyDuplicateFolders(logs::add)
        assertEquals(1, provider.named("snapshot").size)
        assertEquals(1, provider.named("settings.hash.gz").size)
        assertEquals(2, logs.count { it == "Cleanup: deleted snapshot/settings.hash.gz (duplicate)" })
        assertTrue(logs.contains("Cleanup: deleted snapshot/ (empty duplicate folder)"))
        assertEquals(provider.deleted.size, logs.count { it.startsWith("Cleanup: deleted ") })
    }

    @Test fun failedDeletionKeepsDocumentForRetry() = runBlocking {
        val provider = MemoryDocuments()
        val file = provider.add(provider.root, "old.gpx")
        provider.deleteErrors += file.id
        val dir = provider.dir()
        assertTrue(runCatching { dir.deleteFile("old.gpx") }.isFailure)
        assertTrue("old.gpx" in dir.fileNames())
        provider.deleteErrors.clear()
        dir.deleteFile("old.gpx")
        assertFalse("old.gpx" in dir.fileNames())
    }

    @Test fun cleanupUsesOnlyCommittedInventoryAndPreservesUnrelatedFiles() = runBlocking {
        val provider = MemoryDocuments()
        val (oldHeader, oldKey) = BackupCrypto.createWebAuthnHeader(ByteArray(32) { 7 }, ByteArray(32) { 8 }, "old")
        val old = snapshot(provider, "old", header = oldHeader, cipher = BackupCrypto.PartitionCipher(oldKey))
        val current = snapshot(provider, "new")
        val invalid = provider.add(provider.root, "manifest.json", bytes = "broken v1".toByteArray())
        val oldManifest = provider.named("manifest.${old.manifest.generation!!.sequence}.json").single().doc
        val oldInventory = provider.named(old.manifest.inventory.fileName).single().doc
        val currentBlob = provider.named(current.inventory.snapshots.single().fileName).single().doc
        provider.readErrors += listOf(invalid.id, oldManifest.id, oldInventory.id, currentBlob.id)
        val unrelatedRoot = provider.add(provider.root, "notes.txt")
        val unrelatedFolder = provider.add(provider.root, "other", true)
        val unrelatedData = provider.add(unrelatedFolder, "keep.${"a".repeat(64)}.gz")
        val snapshots = provider.named("snapshot").single().doc
        val unrelatedInside = provider.add(snapshots, "notes.txt")

        val logs = mutableListOf<String>()
        val result = BackupArchive.cleanup(provider.dir(), current, logs::add)
        assertTrue(result.toString(), result.complete)
        assertEquals(1, provider.nodes.values.count { it.doc.name.startsWith("manifest.") })
        assertEquals(1, provider.nodes.values.count { it.doc.name.startsWith("inventory.") })
        assertTrue(provider.named(old.inventory.snapshots.single().fileName).isEmpty())
        listOf(unrelatedRoot, unrelatedData, unrelatedInside, currentBlob).forEach { assertTrue(provider.nodes.containsKey(it.id)) }
        assertTrue(logs.contains("Cleanup: deleted ${oldManifest.name}"))
        assertTrue(logs.contains("Cleanup: deleted ${oldInventory.name}"))
        assertTrue(logs.contains("Cleanup: deleted snapshot/${old.inventory.snapshots.single().fileName}"))
        assertEquals(provider.deleted.size, logs.count { it.startsWith("Cleanup: deleted ") })
        assertFalse(logs.any { it.contains("notes.txt") || it.contains(unrelatedData.name) || it.contains("snapshot/${currentBlob.name}") })
    }

    @Test fun cleanupDeleteFailureKeepsNewBackupAndRetriesWithoutRewritingIt() = runBlocking {
        val provider = MemoryDocuments()
        val old = snapshot(provider, "old")
        val current = snapshot(provider, "new", old)
        val oldBlob = provider.named(old.inventory.snapshots.single().fileName).single().doc
        provider.deleteErrors += oldBlob.id
        val logs = mutableListOf<String>()
        assertFalse(BackupArchive.cleanup(provider.dir(), current, logs::add).complete)
        assertTrue(logs.contains("Cleanup: failed to delete snapshot/${oldBlob.name}: delete refused"))
        assertFalse(logs.contains("Cleanup: deleted snapshot/${oldBlob.name}"))
        assertFalse(logs.contains("Cleanup: complete"))
        assertEquals(current.manifest, BackupArchive.recover(provider.dir(), null, null, 1).opened.manifest)
        provider.deleteErrors.clear()
        logs.clear()
        assertTrue(BackupArchive.cleanup(provider.dir(), current, logs::add).complete)
        assertTrue(logs.contains("Cleanup: deleted snapshot/${oldBlob.name}"))
        assertEquals("Cleanup: complete", logs.last())
        assertFalse(provider.nodes.containsKey(oldBlob.id))
    }

    @Test fun onlyMissingOrCorruptPartitionsNeedRepairAndProviderErrorsStillAbort() = runBlocking {
        val provider = MemoryDocuments()
        val folder = provider.add(provider.root, "samples", true)
        val entries = (1..3).map { index ->
            val bytes = "week $index".toByteArray()
            val doc = provider.add(folder, "week$index.gz", bytes = bytes)
            PartitionEntry("samples", index * 7L, "week$index", doc.name, 1, backupHash(bytes), backupHash(bytes))
        }
        provider.named(entries[0].fileName).forEach { provider.nodes.remove(it.doc.id) }
        provider.named(entries[1].fileName).single().bytes = "corrupt".toByteArray()
        assertEquals(entries.take(2), BackupArchive.partitionsToRepair(provider.dir(), entries))
        provider.readErrors += provider.named(entries[2].fileName).single().doc.id
        assertTrue(runCatching { BackupArchive.partitionsToRepair(provider.dir(), entries) }.exceptionOrNull() is BackupStorageException)
    }

    @Test fun checksAreReusedWithinOneBackupButNotAcrossBackups() = runBlocking {
        val provider = MemoryDocuments()
        val file = provider.add(provider.root, "file.gz", bytes = "good".toByteArray())
        val hash = backupHash("good".toByteArray())
        val first = provider.dir()
        assertTrue(first.verifyFile(file.name, hash))
        assertTrue(first.verifyFile(file.name, hash))
        assertEquals(1, provider.reads.size)
        provider.nodes.getValue(file.id).bytes = "damaged".toByteArray()
        assertTrue(runCatching { provider.dir().verifyFile(file.name, hash) }.exceptionOrNull() is BackupContentException)
    }

    @Test fun encryptionChangeDeletesManagedFilesByNameWithoutReadingOrArchiving() = runBlocking {
        val provider = MemoryDocuments()
        val files = mutableListOf(
            provider.add(provider.root, "manifest.json", bytes = "broken v1".toByteArray()),
            provider.add(provider.root, "manifest.99.json", bytes = "broken v2".toByteArray()),
            provider.add(provider.root, "inventory.${"a".repeat(64)}.json.gz"),
            provider.add(provider.root, "inventory.${"b".repeat(64)}.json.gz.enc"),
        )
        for (stream in listOf("samples", "visits", "trips", "snapshot", "trips")) {
            val dir = provider.add(provider.root, stream, true)
            files += provider.add(dir, "2026-W40.${"c".repeat(64)}.jsonl.gz")
            repeat(2) { files += provider.add(dir, "2026-W41.${"d".repeat(64)}.jsonl.gz.enc") }
            provider.add(dir, "notes.txt")
        }
        val archive = provider.add(provider.root, "archive-20261005T000000Z", true)
        val archived = provider.add(archive, "manifest.1.json")
        val unrelated = listOf(provider.add(provider.root, "notes.txt"),
            provider.add(provider.root, "manifest.notes.json"), provider.add(provider.root, "2026-W40.gpx"), archived)
        provider.readErrors += provider.nodes.keys
        val logs = mutableListOf<String>()
        val root = provider.dir()

        BackupArchive.deleteBeforeEncryptionChange(root, logs::add)

        assertEquals(files.map { it.id }.toSet(), provider.deleted.toSet())
        assertTrue(provider.reads.isEmpty())
        assertTrue(provider.writes.isEmpty())
        assertTrue(provider.moved.isEmpty())
        assertEquals(1, provider.nodes.values.count { it.doc.name.startsWith("archive-") })
        unrelated.forEach { assertTrue(provider.nodes.containsKey(it.id)) }
        assertEquals(6, provider.named("notes.txt").size)
        assertEquals(files.size, logs.count { it.startsWith("Encryption change: deleted ") })
        assertTrue(logs.any { it.contains("deleted trips/2026-W40.") })
        // Old inventories may be broken or encrypted with unavailable credentials. New publication
        // still starts at sequence 1 in the same session after the filename-only deletion.
        val (header, key) = BackupCrypto.createWebAuthnHeader(ByteArray(32) { 7 }, ByteArray(32) { 8 }, "new")
        val fresh = BackupArchive.publish(root, header, BackupCrypto.PartitionCipher(key), BackupInventory(), 1, provider.clock, null)
        assertEquals(1L, fresh.generation!!.sequence)
        assertTrue(fresh.inventory.fileName.endsWith(".enc"))
    }

    @Test fun encryptionChangeDeletionFailureStopsAndCanBeRetried() = runBlocking {
        val provider = MemoryDocuments()
        val old = snapshot(provider, "settings")
        val manifest = provider.named("manifest.1.json").single().doc
        provider.deleteErrors += manifest.id
        val reads = provider.reads.size
        val writes = provider.writes.size
        val logs = mutableListOf<String>()
        val root = provider.dir()

        assertTrue(runCatching { BackupArchive.deleteBeforeEncryptionChange(root, logs::add) }.exceptionOrNull() is IOException)
        assertTrue(provider.deleted.isEmpty())
        assertEquals(reads, provider.reads.size)
        assertEquals(writes, provider.writes.size)
        assertTrue(logs.any { it.contains("failed to delete manifest.1.json") })
        assertFalse(logs.any { it.contains("new backup sequence starts") })
        assertEquals(old.manifest, BackupArchive.latest(provider.dir()))

        provider.deleteErrors.clear()
        BackupArchive.deleteBeforeEncryptionChange(root, logs::add)
        assertFalse(BackupArchive.hasBackupFiles(root))
        assertTrue(provider.nodes.values.none { it.doc.name.startsWith("archive-") })
    }

    @Test fun encryptionChangeListingFailureDoesNotStartDeleting() = runBlocking {
        val provider = MemoryDocuments()
        snapshot(provider, "settings")
        val failingProvider = object : BackupDocuments by provider {
            override suspend fun list(parent: BackupDocument): List<BackupDocument> {
                if (parent.name == "snapshot") throw IOException("folder unavailable")
                return provider.list(parent)
            }
        }
        val writes = provider.writes.size

        assertTrue(runCatching {
            BackupArchive.deleteBeforeEncryptionChange(SafDir(failingProvider, provider.root))
        }.exceptionOrNull() is IOException)
        assertTrue(provider.deleted.isEmpty())
        assertEquals(writes, provider.writes.size)
    }

    @Test fun archiveSelectsOnlyManagedFilenamesWithoutInterpretingOldContents() = runBlocking {
        val provider = MemoryDocuments()
        val manifest = provider.add(provider.root, "manifest.99.json", bytes = "broken manifest".toByteArray())
        val inventory = provider.add(provider.root, "inventory.${"a".repeat(16)}.json.gz.enc", bytes = "opaque encrypted inventory".toByteArray())
        val snapshots = provider.add(provider.root, "snapshot", true)
        val blob = provider.add(snapshots, "settings.${"b".repeat(16)}.gz.enc", bytes = "broken encrypted snapshot".toByteArray())
        val unrelated = listOf(provider.add(provider.root, "notes.txt"),
            provider.add(provider.root, "manifest.notes.json"), provider.add(snapshots, "notes.txt"))
        unrelated.forEach { provider.readErrors += it.id }
        val root = provider.dir()
        assertTrue(BackupArchive.hasBackupFiles(root))
        assertTrue(provider.reads.isEmpty())
        val archiveName = requireNotNull(BackupArchive.archiveBeforeFull(root, provider.clock))
        val archive = root.childDirOrNull(archiveName)!!
        assertArrayEquals("broken manifest".toByteArray(), archive.readVerified(manifest.name, backupHash("broken manifest".toByteArray())))
        assertArrayEquals("opaque encrypted inventory".toByteArray(), archive.readVerified(inventory.name, backupHash("opaque encrypted inventory".toByteArray())))
        assertArrayEquals("broken encrypted snapshot".toByteArray(), archive.childDirOrNull("snapshot")!!.readVerified(blob.name, backupHash("broken encrypted snapshot".toByteArray())))
        assertEquals(setOf(manifest.id, inventory.id, blob.id), provider.deleted.toSet())
        unrelated.forEach { assertTrue(provider.nodes.containsKey(it.id)) }
        // Use this same session to catch stale listings influencing sequence selection.
        val fresh = BackupArchive.publish(root, plainHeader, plainCipher, BackupInventory(), 1, provider.clock, null)
        assertEquals(1L, fresh.generation!!.sequence)
        assertTrue(BackupArchive.cleanup(root, BackupArchive.Opened(fresh, BackupInventory())).complete)
        assertArrayEquals("broken manifest".toByteArray(), archive.readVerified(manifest.name, backupHash("broken manifest".toByteArray())))
    }

    @Test fun archivePreservesEncryptedBackupAndEveryDuplicateCopy() = runBlocking {
        val provider = MemoryDocuments()
        val (header, key) = BackupCrypto.createWebAuthnHeader(ByteArray(32) { 7 }, ByteArray(32) { 8 }, "credential")
        val cipher = BackupCrypto.PartitionCipher(key)
        val old = snapshot(provider, "settings", header = header, cipher = cipher)
        val blob = provider.named(old.inventory.snapshots.single().fileName).single()
        val duplicateFolder = provider.add(provider.root, "snapshot", true)
        provider.add(duplicateFolder, blob.doc.name, bytes = blob.bytes)
        provider.add(duplicateFolder, blob.doc.name, bytes = "corrupt duplicate".toByteArray())
        val root = provider.dir()
        val archive = root.childDirOrNull(requireNotNull(BackupArchive.archiveBeforeFull(root, provider.clock)))!!
        assertEquals(old.manifest, BackupArchive.latest(archive))
        BackupArchive.validate(archive, BackupArchive.inventory(archive, old.manifest, cipher), cipher)
        assertEquals(2, archive.childDirOrNull("snapshot")!!.folders.size)
        assertEquals(3, archive.childDirOrNull("snapshot")!!.documents().size)
        assertTrue(root.childDirOrNull("snapshot")!!.documents().isEmpty())
    }

    @Test fun archiveCopyFailureNeverDeletesOriginals() = runBlocking {
        val provider = MemoryDocuments()
        val old = snapshot(provider, "settings")
        provider.writeErrorFor = "settings."
        assertTrue(runCatching { BackupArchive.archiveBeforeFull(provider.dir(), provider.clock) }.isFailure)
        assertTrue(provider.deleted.isEmpty())
        assertEquals(old.manifest, BackupArchive.recover(provider.dir(), null, null, 1).opened.manifest)
    }

    @Test fun archiveRejectsDamagedCopyBeforeDeletingAnything() = runBlocking {
        val provider = MemoryDocuments()
        val old = snapshot(provider, "settings")
        provider.corruptWrites = true
        assertTrue(runCatching { BackupArchive.archiveBeforeFull(provider.dir(), provider.clock) }.isFailure)
        assertTrue(provider.deleted.isEmpty())
        assertEquals(old.manifest, BackupArchive.recover(provider.dir(), null, null, 1).opened.manifest)
    }

    @Test fun archiveDeleteFailureLeavesACompleteRestorableArchive() = runBlocking {
        val provider = MemoryDocuments()
        val old = snapshot(provider, "settings")
        val blob = provider.named(old.inventory.snapshots.single().fileName).single().doc
        provider.deleteErrors += blob.id
        assertTrue(runCatching { BackupArchive.archiveBeforeFull(provider.dir(), provider.clock) }.isFailure)
        val root = provider.dir()
        val archiveName = root.documents().single { it.directory && it.name.startsWith("archive-") }.name
        assertEquals(old.manifest, BackupArchive.recover(root.childDirOrNull(archiveName)!!, null, null, 1).opened.manifest)
        assertTrue(provider.nodes.containsKey(blob.id))
        assertNull(BackupArchive.latest(root))
        provider.deleteErrors.clear()
        assertNotEquals(archiveName, BackupArchive.archiveBeforeFull(provider.dir(), provider.clock))
        assertEquals(old.manifest, BackupArchive.recover(provider.dir().childDirOrNull(archiveName)!!, null, null, 1).opened.manifest)
    }

    @Test fun archiveDoesNotCreateFoldersWhenThereAreNoBackupFiles() = runBlocking {
        val provider = MemoryDocuments()
        provider.add(provider.root, "samples", true)
        provider.add(provider.root, "notes.txt")
        provider.add(provider.root, "archive-existing", true)
        val originalIds = provider.nodes.keys.toSet()
        assertFalse(BackupArchive.hasBackupFiles(provider.dir()))
        assertNull(BackupArchive.archiveBeforeFull(provider.dir(), provider.clock))
        assertEquals(originalIds, provider.nodes.keys)
    }

    @Test fun nativeArchiveMovesWholeFoldersWithoutReadingOrRewritingFiles() = runBlocking {
        val provider = MemoryDocuments().apply { supportsMoves = true }
        val old = snapshot(provider, "settings")
        val duplicate = provider.add(provider.root, "snapshot", true)
        val oldBlob = provider.named(old.inventory.snapshots.single().fileName).single()
        provider.add(duplicate, oldBlob.doc.name, bytes = oldBlob.bytes)
        val originalCount = provider.nodes.size
        provider.reads.clear()
        provider.readErrors += provider.nodes.keys
        val root = provider.dir()
        val archiveName = requireNotNull(BackupArchive.archiveBeforeFull(root, provider.clock))
        assertTrue(provider.reads.isEmpty())
        assertTrue(provider.deleted.isEmpty())
        assertEquals(originalCount + 1, provider.nodes.size) // Only the archive directory was created.
        assertEquals(2, provider.moved.count { it == "snapshot" })
        assertNull(root.childDirOrNull("snapshot"))
        assertTrue(root.fileNames().isEmpty())
        provider.readErrors.clear()
        val archive = root.childDirOrNull(archiveName)!!
        assertEquals(2, archive.childDirOrNull("snapshot")!!.folders.size)
        assertEquals(old.manifest, BackupArchive.recover(archive, null, null, 1).opened.manifest)
        assertEquals(1L, snapshot(provider, "new").manifest.generation!!.sequence)
    }

    @Test fun nativeArchiveClearsPinnedIdsBeforeCreatingNewBackupFolders() = runBlocking {
        val provider = MemoryDocuments().apply { supportsMoves = true }
        snapshot(provider, "settings")
        val pins = mutableMapOf<Pair<String, String>, String>()
        val identities = object : DirectoryIdentities {
            override fun get(parent: String, name: String) = pins[parent to name]
            override fun put(parent: String, name: String, id: String) { pins[parent to name] = id }
            override fun remove(parent: String, name: String) { pins.remove(parent to name) }
        }
        val root = SafDir(provider, provider.root, identities)
        val oldId = root.childDir("snapshot").canonical.id
        val archiveName = requireNotNull(BackupArchive.archiveBeforeFull(root, provider.clock))
        val newFolder = root.childDir("snapshot")
        assertNotEquals(oldId, newFolder.canonical.id)
        assertTrue(newFolder.documents().isEmpty())
        assertEquals(oldId, root.childDirOrNull(archiveName)!!.childDirOrNull("snapshot")!!.canonical.id)
    }

    @Test fun failedNativeMoveRollsBackEarlierMovesIncludingChangedDocumentIds() = runBlocking {
        val provider = MemoryDocuments().apply { supportsMoves = true; changeIdsOnMove = true }
        val old = snapshot(provider, "settings")
        provider.moveErrors += provider.named("manifest.1.json").single().doc.id
        assertTrue(runCatching { BackupArchive.archiveBeforeFull(provider.dir(), provider.clock) }.isFailure)
        assertTrue(provider.deleted.isEmpty())
        assertEquals(old.manifest, BackupArchive.recover(provider.dir(), null, null, 1).opened.manifest)
    }

    @Test fun mixedFolderFallsBackToFileCopiesAndLeavesUnrelatedContentsInPlace() = runBlocking {
        val provider = MemoryDocuments().apply { supportsMoves = true }
        val old = snapshot(provider, "settings")
        val folder = provider.named("snapshot").single().doc
        val unrelated = provider.add(folder, "notes.txt")
        val archiveName = requireNotNull(BackupArchive.archiveBeforeFull(provider.dir(), provider.clock))
        assertTrue(provider.moved.isEmpty())
        assertEquals(folder.id, provider.nodes.getValue(unrelated.id).parent)
        assertEquals(old.manifest, BackupArchive.recover(provider.dir().childDirOrNull(archiveName)!!, null, null, 1).opened.manifest)
    }
}
