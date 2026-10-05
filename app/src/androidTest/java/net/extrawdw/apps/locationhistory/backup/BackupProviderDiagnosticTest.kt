package net.extrawdw.apps.locationhistory.backup

import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room3.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import net.extrawdw.apps.locationhistory.core.coordinates.Gcj02CoordinateTransform
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateAdapter
import net.extrawdw.apps.locationhistory.core.coordinates.MainlandChinaRegion
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.data.repo.BackupRepository
import net.extrawdw.apps.locationhistory.data.repo.BackupResult
import net.extrawdw.apps.locationhistory.data.repo.EncryptionChoice
import net.extrawdw.apps.locationhistory.data.repo.LegacyPlaceCoordinateManager
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import net.extrawdw.apps.locationhistory.security.BackupCrypto
import net.extrawdw.apps.locationhistory.security.BackupKeyVault
import net.extrawdw.apps.locationhistory.security.BackupEncryption
import net.extrawdw.apps.locationhistory.security.CryptoHeader
import net.extrawdw.apps.locationhistory.security.MapsApiKeyVault
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Opt-in diagnosis and isolated provider regression tests; existing backup files are never edited. */
class BackupProviderDiagnosticTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    @Test fun inspectExistingTrees() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("inspectExisting") != "true") return@runBlocking
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun report(message: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        }
        val grants = context.contentResolver.persistedUriPermissions.filter {
            DocumentsContract.isTreeUri(it.uri)
        }
        report("Tree grants: ${grants.size}")
        val config = SettingsRepository(context).backupConfig.first()
        val header = config.cryptoHeaderJson?.let { json.decodeFromString(CryptoHeader.serializer(), it) }
        val cachedKey = BackupKeyVault(context).localDek()
        grants.forEachIndexed { index, grant ->
            try {
                report("Tree $index provider: ${grant.uri.authority}")
                val root = SafBackupStore(context).open(grant.uri, writable = false)
                if (root == null) { report("Tree $index: inaccessible"); return@forEachIndexed }
                report("Tree $index: ${root.fileNames().size} files")
                val scan = BackupArchive.scan(root)
                report("Tree $index: ${scan.records.size} valid manifests, ${scan.invalid.size} invalid manifests, formats ${scan.records.groupingBy { it.manifest.formatVersion }.eachCount()}")
                for ((recordIndex, record) in scan.records.withIndex()) {
                    val manifest = record.manifest
                    val key = when {
                        manifest.crypto.mode == BackupEncryption.NONE -> null
                        manifest.crypto == header && cachedKey != null -> cachedKey
                        else -> { report("Generation $recordIndex: ${manifest.crypto.mode}, different or unavailable key"); continue }
                    }
                    try {
                        val cipher = BackupCrypto.PartitionCipher(key)
                        val inventory = BackupArchive.inventory(root, manifest, cipher)
                        BackupArchive.validate(root, inventory, cipher)
                        report("Generation $recordIndex: ${manifest.crypto.mode}, contents valid")
                    } catch (e: Exception) {
                        report("Generation $recordIndex: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                report("Tree $index: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(10).joinToString("\n")}")
                throw e
            }
        }
    }

    /** Opt-in device regression: only writes synthetic data to a new, temporary child folder. */
    @Test fun repeatedOneTimeDumpWithLegacyBackup() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("verifyWrites") != "true") return@runBlocking
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = InstrumentationRegistry.getArguments().getString("providerAuthority") ?: "com.android.externalstorage.documents"
        val grant = context.contentResolver.persistedUriPermissions.first {
            it.isWritePermission && DocumentsContract.isTreeUri(it.uri) &&
                it.uri.authority == authority
        }
        val store = SafBackupStore(context)
        val parent = requireNotNull(store.open(grant.uri))
        val testName = "Pathline-regression-${UUID.randomUUID()}"
        val root = parent.childDir(testName)
        System.loadLibrary("sqlcipher")
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(SQLCipherDriver("backup-regression-only".toByteArray(), null, null)).build()
        try {
            val settings = SettingsRepository(context)
            val legacy = LegacyPlaceCoordinateManager(db, db.placeDao(), db.visitDao(),
                db.placeCoordinateRepairDao(), GoogleAndroidCoordinateAdapter(Gcj02CoordinateTransform(MainlandChinaRegion(context))),
                TimelineWriteLock())
            // Reads only app settings. The database is isolated in memory and protection is NONE,
            // so neither user history nor key material is included in these test dumps.
            val engine = BackupEngine(context, db, db.backupDao(), settings, MapsApiKeyVault(context), legacy)
            val repository = BackupRepository(context, settings, engine, store, BackupKeyVault(context), db.backupDao())
            db.backupDao().restoreSamples((0..2).map { index ->
                json.decodeFromString(LocationSampleEntity.serializer(), """{"id":${index + 1},"timestampMs":${(20_000L + 7 * index) * 86_400_000},"dayEpoch":${20_000 + 7 * index},"latitude":1.0,"longitude":2.0,"isMock":false,"elapsedRealtimeNanos":0,"devicePhysicalState":"UNKNOWN","devicePhysicalStateConfidence":0.0}""")
            })
            val cipher = BackupCrypto.PartitionCipher(null)
            val inventory = BackupArchive.encodeBlob("{\"partitions\":[],\"snapshots\":[]}".toByteArray(), cipher)
            val legacyInventoryName = "inventory.${"0".repeat(16)}.json.gz"
            root.writeImmutable(legacyInventoryName, inventory)
            val unsigned = """{"formatVersion":1,"schemaVersion":1,"createdAtMs":1,"crypto":{"version":1,"mode":"NONE","password":null,"passkey":null},"inventory":{"fileName":"$legacyInventoryName","sha256":"${backupHash(inventory)}"},"checksum":""}"""
            root.writeImmutable("manifest.json", unsigned.replace("\"checksum\":\"\"",
                "\"checksum\":\"${backupHash(unsigned.toByteArray())}\"").toByteArray(), "application/json")
            repeat(3) { index ->
                assertTrue(repository.backupExistsAt(grant.uri, testName))
                val result = repository.oneTimeDump(grant.uri, testName, EncryptionChoice.None, BackupReporter.None)
                assertTrue(result.toString(), result is BackupResult.Backed)
                assertFalse((result as BackupResult.Backed).report.cleanupPending)
                val fresh = requireNotNull(store.open(grant.uri)).childDirOrNull(testName)!!
                assertEquals(1L, BackupArchive.latest(fresh)!!.generation!!.sequence)
                assertEquals(1, BackupArchive.scan(fresh).records.size)
                assertEquals(1, fresh.fileNames().count { it.startsWith("inventory.") })
                assertTrue(repository.backupExistsAt(grant.uri, testName))
                BackupArchive.recover(fresh, null, null, AppDatabase.SCHEMA_VERSION)
                val archives = fresh.documents().filter { it.directory && it.name.startsWith("archive-") }
                assertEquals(index + 1, archives.size)
                for (archive in archives) {
                    BackupArchive.recover(fresh.childDirOrNull(archive.name)!!, null, null, AppDatabase.SCHEMA_VERSION)
                }
            }
            // No dirty rows: deleting one weekly file and a snapshot must repair incrementally.
            db.backupDao().clearAllDirty()
            val damaged = requireNotNull(store.open(grant.uri)).childDirOrNull(testName)!!
            val before = BackupArchive.recover(damaged, null, null, AppDatabase.SCHEMA_VERSION).opened.inventory
            assertEquals(3, before.partitions.size)
            val missing = before.partitions.first()
            damaged.childDirOrNull(missing.stream)!!.deleteFile(missing.fileName)
            val snapshots = damaged.childDirOrNull("snapshot")!!
            snapshots.delete(snapshots.documents().first { !it.directory })
            val material = BackupEngine.Material(BackupCrypto.plaintextHeader(), null)
            val repaired = engine.runIncremental(requireNotNull(store.open(grant.uri)).childDirOrNull(testName)!!,
                material, System.currentTimeMillis())
            assertEquals(1, repaired.partitionsWritten)
            assertEquals(0, repaired.partitionsFailed)
            assertFalse(repaired.cleanupPending)
            val fresh = requireNotNull(store.open(grant.uri)).childDirOrNull(testName)!!
            val recovered = BackupArchive.recover(fresh, null, null, AppDatabase.SCHEMA_VERSION)
            assertFalse(recovered.recovered)
            assertEquals(before.partitions.drop(1), recovered.opened.inventory.partitions.drop(1))
            // A corrupt file also gets replaced without re-exporting healthy weeks.
            val corrupt = recovered.opened.inventory.partitions.last()
            fresh.childDirOrNull(corrupt.stream)!!.writeFile(corrupt.fileName, "application/octet-stream") { it.write("broken".toByteArray()) }
            val repairedCorrupt = engine.runIncremental(requireNotNull(store.open(grant.uri)).childDirOrNull(testName)!!,
                material, System.currentTimeMillis())
            assertEquals(1, repairedCorrupt.partitionsWritten)
            assertFalse(repairedCorrupt.cleanupPending)
            val finalRoot = requireNotNull(store.open(grant.uri)).childDirOrNull(testName)!!
            assertEquals(3, BackupArchive.recover(finalRoot, null, null, AppDatabase.SCHEMA_VERSION).opened.inventory.partitions.size)
            assertEquals(3, finalRoot.childDirOrNull("samples")!!.fileNames().size)
            assertEquals(3L, BackupArchive.latest(finalRoot)!!.generation!!.sequence)
            assertEquals(3, finalRoot.documents().count { it.directory && it.name.startsWith("archive-") })
            // Full backup must also archive unreadable-format bytes without interpreting them.
            val oldManifest = BackupArchive.latest(finalRoot)!!
            val oldArchives = finalRoot.documents().filter { it.directory && it.name.startsWith("archive-") }.map { it.id }.toSet()
            val broken = "broken backup bytes".toByteArray()
            finalRoot.writeFile(oldManifest.inventory.fileName, "application/octet-stream") { it.write(broken) }
            finalRoot.writeFile("manifest.3.json", "application/json") { it.write(broken) }
            assertTrue(repository.backupExistsAt(grant.uri, testName))
            assertTrue(repository.oneTimeDump(grant.uri, testName, EncryptionChoice.None, BackupReporter.None) is BackupResult.Backed)
            val rebuilt = requireNotNull(store.open(grant.uri)).childDirOrNull(testName)!!
            assertEquals(1L, BackupArchive.recover(rebuilt, null, null, AppDatabase.SCHEMA_VERSION).opened.manifest.generation!!.sequence)
            val newArchive = rebuilt.documents().single { it.directory && it.name.startsWith("archive-") && it.id !in oldArchives }
            val archived = rebuilt.childDirOrNull(newArchive.name)!!
            assertArrayEquals(broken, archived.readVerified(oldManifest.inventory.fileName, backupHash(broken)))
            assertArrayEquals(broken, archived.readVerified("manifest.3.json", backupHash(broken)))
        } finally {
            db.close()
            assertTrue("Temporary test folder must be removed",
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(root.canonical.id)))
        }
    }
}
