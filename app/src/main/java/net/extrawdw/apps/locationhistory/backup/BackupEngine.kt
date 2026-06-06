package net.extrawdw.apps.locationhistory.backup

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.BackupDao
import net.extrawdw.apps.locationhistory.data.db.BackupDirtyPartitionEntity
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.ml.LiteRtModelStore
import net.extrawdw.apps.locationhistory.security.BackupCrypto
import net.extrawdw.apps.locationhistory.security.CryptoHeader
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a backup run. */
data class BackupReport(
    val partitionsWritten: Int,
    val partitionsFailed: Int,
    val totalPartitions: Int,
)

/** Outcome of a restore. */
data class RestoreReport(val partitionsRestored: Int, val rowsRestored: Int)

/**
 * Reads/writes the structured backup described by [BackupManifest].
 *
 * Two write modes:
 *  - **incremental** ([runIncremental]) — claims the dirty-partition set the triggers maintain and
 *    re-emits only those (stream, week) files, merging into the existing inventory. The standard
 *    periodic path; only the latest week(s) change for an active recorder.
 *  - **full** ([runFull]) — re-emits every populated week. Used for the one-time database dump and
 *    for reclaim reconciliation when a SAF grant was lost.
 *
 * **Crash-atomic, write-once layout.** SAF/cloud (notably Drive) has no atomic file replace, so we
 * never mutate a file the current manifest still references. Every partition/snapshot/inventory file
 * is **content-addressed** — its name embeds a hash of its on-disk bytes (`week.<hash>.jsonl.gz.enc`)
 * — and therefore immutable. A run only *adds* files (unchanged content is reused, never re-uploaded);
 * the fixed-name `manifest.json`, written **last**, is the single commit point that atomically swaps to
 * the new backup. Superseded files are [pruneOrphans]'d *after* the commit. An interrupted run thus
 * leaves either the previous backup or the new one fully restorable — never a half-mutated one. The
 * unique names also make duplicate same-name files impossible by construction.
 *
 * The manifest is split in two: a minimal **public** `manifest.json` (versions + crypto header +
 * a hash-checked pointer to the inventory) and an **encrypted** [BackupInventory] listing every
 * file, so an encrypted backup leaks nothing about its contents from plaintext. Each inventory entry
 * carries two hashes: a `sha256` over the *uncompressed, unencrypted* serialized bytes (deterministic
 * and recomputable from the DB, for reclaim) and an `encSha256` over the on-disk gzip-then-encrypt
 * bytes (verified before a file is decrypted on restore, and used as the content-address). Files on
 * disk are gzip-then-encrypt.
 */
@Singleton
class BackupEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val backupDao: BackupDao,
    private val settingsRepository: SettingsRepository,
    private val modelStore: LiteRtModelStore,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Crypto material for one run: the manifest header plus a cipher bound to its DEK. */
    class Material(val header: CryptoHeader, dek: ByteArray?) {
        val cipher = BackupCrypto.PartitionCipher(dek)
    }

    /** [runIncremental] couldn't read the existing inventory; the caller should fall back to a full backup. */
    class IncrementalInventoryUnavailable(cause: Throwable) : Exception(cause)

    // -- Public API ---------------------------------------------------------------------------

    /**
     * Read the public manifest. [root] is the directory that directly contains `manifest.json` (the
     * resolved subdir). Returns null when there is no readable, integrity-valid manifest — a failed
     * self-checksum is treated as "no usable backup" for the lenient callers (existence / merge);
     * [restore] re-validates and surfaces a precise error instead.
     */
    suspend fun readManifest(root: SafDir): BackupManifest? {
        val manifest = parseManifest(root) ?: return null
        if (!checksumMatches(manifest)) {
            AppLog.w(TAG, "manifest failed integrity check (checksum mismatch); ignoring")
            return null
        }
        return manifest
    }

    private fun parseManifest(root: SafDir): BackupManifest? {
        val bytes = root.readFile(MANIFEST)?.use { it.readBytes() } ?: return null
        return runCatching {
            json.decodeFromString(
                BackupManifest.serializer(),
                bytes.decodeToString()
            )
        }
            .getOrNull()
    }

    /** Canonical bytes used both to compute and to verify the public manifest's self-checksum. */
    private fun manifestChecksum(manifest: BackupManifest): String =
        sha256(
            json.encodeToString(BackupManifest.serializer(), manifest.copy(checksum = ""))
                .encodeToByteArray()
        )

    private fun checksumMatches(manifest: BackupManifest): Boolean =
        manifest.checksum == manifestChecksum(manifest)

    /**
     * Read and decrypt the inventory referenced by [manifest], verifying the on-disk file hash first.
     * [cipher] must be bound to the DEK recovered from the manifest's crypto header.
     */
    private fun readInventory(
        root: SafDir, manifest: BackupManifest, cipher: BackupCrypto.PartitionCipher,
    ): BackupInventory {
        val ref = manifest.inventory
        val raw = root.readFile(ref.fileName)?.use { it.readBytes() }
            ?: error("missing inventory file ${ref.fileName}")
        verifyHash(ref.fileName, raw, ref.sha256)
        val plain = readBlob(raw.inputStream(), cipher)
        return json.decodeFromString(BackupInventory.serializer(), plain.decodeToString())
    }

    suspend fun runIncremental(
        root: SafDir,
        material: Material,
        nowMs: Long,
        reporter: BackupReporter = BackupReporter.None,
    ): BackupReport {
        val existing = readManifest(root)
        // The incremental merge must know the COMPLETE previous file set (so it neither drops nor
        // prunes unchanged weeks). If the inventory can't be read, bail to a full backup instead of
        // committing a lossy manifest. (Encryption-mode changes already route to full, so this is rare.)
        val previous = if (existing == null) {
            BackupInventory()
        } else {
            runCatching { readInventory(root, existing, material.cipher) }
                .getOrElse { throw IncrementalInventoryUnavailable(it) }
        }
        val prevPartitions = previous.partitions.associateBy { it.stream + "/" + it.weekStart }
        val prevSnapshots = previous.snapshots.associateBy { it.name }

        val merged = LinkedHashMap<String, PartitionEntry>()
        previous.partitions.forEach { merged[it.stream + "/" + it.weekStart] = it }

        val claimed = backupDao.claimDirty()
        val total = (claimed.size + 1).coerceAtLeast(1)
        reporter.log("Incremental backup: ${claimed.size} changed partition(s)")
        var written = 0
        var failed = 0
        var done = 0
        for (dirty in claimed) {
            val key = dirty.stream + "/" + dirty.weekStart
            val label = dirty.stream + "/" + TimeBuckets.weekKey(dirty.weekStart)
            try {
                val entry = emitPartition(
                    root,
                    dirty.stream,
                    dirty.weekStart,
                    material,
                    prevPartitions[key]
                )
                if (entry == null) merged.remove(key) else merged[key] = entry
                written++
                reporter.log("Backed up $label (${entry?.rowCount ?: 0} rows)")
            } catch (t: Throwable) {
                AppLog.w(TAG, "partition $label failed: ${t.message}")
                backupDao.markDirty(listOf(dirty)) // retry next run; previous entry stays referenced
                failed++
                reporter.log("FAILED $label: ${t.message}")
            }
            reporter.progress(++done / total.toFloat())
        }

        reporter.log("Writing snapshots…")
        val snapshots = writeSnapshots(root, material, prevSnapshots)
        val partitions = merged.values.toList()
        val invName =
            writeManifest(root, material, partitions, snapshots, nowMs, existing?.inventory)
        pruneOrphans(root, partitions, snapshots, invName)
        reporter.progress(1f)
        AppLog.i(TAG, "incremental backup: wrote=$written failed=$failed total=${partitions.size}")
        return BackupReport(written, failed, partitions.size)
    }

    suspend fun runFull(
        root: SafDir, material: Material, nowMs: Long, clearDirtyAfter: Boolean,
        reporter: BackupReporter = BackupReporter.None,
    ): BackupReport {
        // Best-effort previous inventory: a full backup re-emits every week regardless, so an
        // unreadable inventory just means "no reuse" (everything is rewritten), which is always safe.
        val existing = readManifest(root)
        val previous =
            existing?.let { runCatching { readInventory(root, it, material.cipher) }.getOrNull() }
                ?: BackupInventory()
        val prevPartitions = previous.partitions.associateBy { it.stream + "/" + it.weekStart }
        val prevSnapshots = previous.snapshots.associateBy { it.name }

        val weeksByStream = mapOf(
            STREAM_SAMPLES to backupDao.sampleWeeks(),
            STREAM_VISITS to backupDao.visitWeeks(),
            STREAM_TRIPS to backupDao.tripWeeks(),
        )
        val total = (weeksByStream.values.sumOf { it.size } + 1).coerceAtLeast(1)
        reporter.log("Full backup: $total partition group(s)")
        val entries = ArrayList<PartitionEntry>()
        var failed = 0
        var done = 0
        for ((stream, weeks) in weeksByStream) {
            for (week in weeks) {
                try {
                    emitPartition(
                        root,
                        stream,
                        week,
                        material,
                        prevPartitions["$stream/$week"]
                    )?.let {
                        entries.add(it)
                        reporter.log("Backed up $stream/${it.weekKey} (${it.rowCount} rows)")
                    }
                } catch (t: Throwable) {
                    val label = "$stream/${TimeBuckets.weekKey(week)}"
                    AppLog.w(TAG, "full: $label failed: ${t.message}")
                    failed++
                    reporter.log("FAILED $label: ${t.message}")
                }
                reporter.progress(++done / total.toFloat())
            }
        }
        reporter.log("Writing snapshots…")
        val snapshots = writeSnapshots(root, material, prevSnapshots)
        val invName = writeManifest(root, material, entries, snapshots, nowMs, existing?.inventory)
        pruneOrphans(root, entries, snapshots, invName)
        if (clearDirtyAfter) backupDao.clearAllDirty()
        reporter.progress(1f)
        AppLog.i(TAG, "full backup: wrote=${entries.size} failed=$failed")
        return BackupReport(entries.size, failed, entries.size)
    }

    /**
     * Write one open-format, **unencrypted** GPX track file per week directly into [dir] (a dedicated
     * SAF location independent of any backup). When [weeks] is null every populated sample week is
     * exported; otherwise only the given weeks. Files are weekly and named by ISO week key, so a
     * re-export simply overwrites the affected weeks. Returns the number of week files written.
     */
    suspend fun exportGpx(
        dir: SafDir,
        weeks: Collection<Long>?,
        reporter: BackupReporter = BackupReporter.None
    ): Int {
        val targetWeeks = (weeks ?: backupDao.sampleWeeks()).sorted()
        val total = targetWeeks.size.coerceAtLeast(1)
        var count = 0
        var done = 0
        for (week in targetWeeks) {
            val (startDay, endExclusive) = week to (week + 7)
            val samples =
                backupDao.samplesForDays(startDay, endExclusive).filter { it.includedInComputation }
            val name = TimeBuckets.weekKey(week) + ".gpx"
            if (samples.isEmpty()) {
                dir.deleteFile(name)
            } else {
                dir.writeFile(name, "application/gpx+xml") { out ->
                    GpxExporter.write(
                        samples,
                        out
                    )
                }
                reporter.log("Exported $name (${samples.size} points)")
                count++
            }
            reporter.progress(++done / total.toFloat())
        }
        return count
    }

    /**
     * Restore the database **as-is** from the backup under [root]. Wipes current recorded/derived data
     * and re-inserts every row with its original primary key so all relational links survive.
     */
    suspend fun restore(
        root: SafDir, password: CharArray?, prfSecret: ByteArray? = null,
        reporter: BackupReporter = BackupReporter.None,
    ): RestoreReport {
        val manifest = parseManifest(root) ?: error("no backup found in the selected folder")
        require(checksumMatches(manifest)) { "backup manifest failed integrity check (checksum mismatch)" }
        require(manifest.formatVersion <= Constants.BACKUP_FORMAT_VERSION) {
            "backup uses a newer on-disk format (v${manifest.formatVersion}); update the app to restore it"
        }
        require(manifest.schemaVersion <= AppDatabase.SCHEMA_VERSION) {
            "backup was made by a newer app version (schema ${manifest.schemaVersion}); update first"
        }
        // FORWARD-COMPAT SEAM: today restore deserializes JSONL straight into the *current* entity
        // classes (ignoreUnknownKeys + per-field defaults absorb minor older-backup drift; undecodable
        // rows are skipped, see decodeLines). Once the schema bumps past v1, an OLDER backup
        // (manifest.schemaVersion < SCHEMA_VERSION) should instead be rebuilt at its own version from
        // app/schemas/<N>.json and run through AppMigrations (restore-then-migrate) — implement that
        // branch here alongside the first migration; the same-version path below stays as-is.
        val dek = BackupCrypto.openDek(manifest.crypto, password, prfSecret)
        val cipher = BackupCrypto.PartitionCipher(dek)
        // The inventory's on-disk hash and (when encrypted) its GCM tag are both verified here,
        // before any file it lists is touched.
        val inventory = readInventory(root, manifest, cipher)
        reporter.log("Restoring ${inventory.partitions.size} partition(s)…")

        val total = (inventory.partitions.size + 1).coerceAtLeast(1)
        var rows = 0
        var done = 0
        db.withTransaction {
            backupDao.wipeForRestore()
            // Time-series partitions first (visits, trips, samples), then snapshots (places,
            // geofences, models) below. No FK constraints are enforced today, so order is not
            // load-bearing; if FKs are ever added, places must be restored before visits/trips.
            for (stream in listOf(STREAM_VISITS, STREAM_TRIPS, STREAM_SAMPLES)) {
                inventory.partitions.filter { it.stream == stream }.forEach { entry ->
                    rows += restorePartition(root, entry, cipher)
                    reporter.log("Restored ${entry.stream}/${entry.weekKey} (${entry.rowCount} rows)")
                    reporter.progress(++done / total.toFloat())
                }
            }
            restoreSnapshots(root, inventory.snapshots, cipher)
            // Repair trip->visit links that the backup itself carried broken (older exports, or data
            // imported from a previous app version): detach any trip endpoint whose visit is absent so
            // the restored DB is self-consistent rather than reproducing the dangling references.
            backupDao.detachDanglingTripVisits()
            // The REPLACE inserts re-fire the dirty triggers; clear so we don't immediately
            // re-upload the whole history we just pulled down.
            backupDao.clearAllDirty()
        }
        restoreSettingsAndModels(root, inventory.snapshots, cipher)
        reporter.progress(1f)
        AppLog.i(TAG, "restore complete: partitions=${inventory.partitions.size} rows=$rows")
        return RestoreReport(inventory.partitions.size, rows)
    }

    // -- Partition emit / restore -------------------------------------------------------------

    private suspend fun emitPartition(
        root: SafDir,
        stream: String,
        weekStart: Long,
        material: Material,
        previous: PartitionEntry?,
    ): PartitionEntry? {
        val endDay = weekStart + 7
        val (bytes, rowCount) = when (stream) {
            STREAM_SAMPLES -> encodeLines(backupDao.samplesForDays(weekStart, endDay))
            STREAM_VISITS -> encodeLines(backupDao.visitsForDays(weekStart, endDay))
            STREAM_TRIPS -> encodeLines(backupDao.tripsForDays(weekStart, endDay))
            else -> error("unknown stream $stream")
        }
        if (rowCount == 0) return null // now-empty week: drop from inventory; prune deletes the old file
        val disk = blobBytes(material, bytes)
        val encHash = sha256(disk)
        val dir = root.childDir(stream)
        // Unchanged since the last committed backup -> reuse the existing (known-good) file, no re-upload.
        if (previous != null && previous.encSha256 == encHash && dir.exists(previous.fileName)) return previous
        val weekKey = TimeBuckets.weekKey(weekStart)
        val fileName = contentName(weekKey, "jsonl.gz", disk, material)
        dir.writeFile(fileName, "application/octet-stream") { out -> out.write(disk) }
        return PartitionEntry(
            stream,
            weekStart,
            weekKey,
            fileName,
            rowCount,
            sha256(bytes),
            encHash
        )
    }

    private suspend fun restorePartition(
        root: SafDir, entry: PartitionEntry, cipher: BackupCrypto.PartitionCipher,
    ): Int {
        val dir = root.childDir(entry.stream)
        val raw = dir.readFile(entry.fileName)?.use { it.readBytes() }
            ?: error("missing partition file ${entry.fileName}")
        verifyHash(entry.fileName, raw, entry.encSha256)          // on-disk bytes, before decrypt
        val bytes = readBlob(raw.inputStream(), cipher)
        verifyHash(entry.fileName, bytes, entry.sha256)           // plaintext, after decrypt
        return when (entry.stream) {
            STREAM_SAMPLES -> decodeLines(
                bytes,
                net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity.serializer()
            )
                .also { backupDao.restoreSamples(it) }.size

            STREAM_VISITS -> decodeLines(
                bytes,
                net.extrawdw.apps.locationhistory.data.db.VisitEntity.serializer()
            )
                .also { backupDao.restoreVisits(it) }.size

            STREAM_TRIPS -> decodeLines(
                bytes,
                net.extrawdw.apps.locationhistory.data.db.TripEntity.serializer()
            )
                .also { backupDao.restoreTrips(it) }.size

            else -> error("unknown stream ${entry.stream}")
        }
    }

    // -- Snapshots (small whole-table / whole-blob data, rewritten every run) -----------------

    private suspend fun writeSnapshots(
        root: SafDir, material: Material, previous: Map<String, SnapshotEntry>,
    ): List<SnapshotEntry> {
        val dir = root.childDir(SNAPSHOT_DIR)
        val out = ArrayList<SnapshotEntry>()

        out += snapshotLines(
            dir,
            material,
            SNAP_PLACES,
            backupDao.allPlaces(),
            previous[SNAP_PLACES]
        )
        out += snapshotLines(
            dir,
            material,
            SNAP_GEOFENCES,
            backupDao.allGeofences(),
            previous[SNAP_GEOFENCES]
        )
        out += snapshotLines(
            dir,
            material,
            SNAP_STATE_EXAMPLES,
            backupDao.allStateExamples(),
            previous[SNAP_STATE_EXAMPLES]
        )
        out += snapshotLines(
            dir,
            material,
            SNAP_TRANSPORT_EXAMPLES,
            backupDao.allTransportExamples(),
            previous[SNAP_TRANSPORT_EXAMPLES]
        )

        // App settings
        val settings =
            BackupSettings(powerProfile = settingsRepository.settings.first().powerProfile.name)
        val settingsBytes =
            json.encodeToString(BackupSettings.serializer(), settings).encodeToByteArray()
        out += snapshotBlob(
            dir,
            material,
            SNAP_SETTINGS,
            settingsBytes,
            rowCount = 1,
            previous[SNAP_SETTINGS]
        )

        // ML checkpoints (raw bytes)
        modelStore.stateCheckpoint.takeIf { it.exists() }?.let {
            out += snapshotBlob(
                dir,
                material,
                SNAP_STATE_CKPT,
                it.readBytes(),
                rowCount = 1,
                previous[SNAP_STATE_CKPT]
            )
        }
        modelStore.transportCheckpoint.takeIf { it.exists() }?.let {
            out += snapshotBlob(
                dir,
                material,
                SNAP_TRANSPORT_CKPT,
                it.readBytes(),
                rowCount = 1,
                previous[SNAP_TRANSPORT_CKPT]
            )
        }
        return out
    }

    private suspend fun restoreSnapshots(
        root: SafDir, snapshots: List<SnapshotEntry>, cipher: BackupCrypto.PartitionCipher,
    ) {
        val dir = root.childDir(SNAPSHOT_DIR)
        fun bytesOf(name: String): ByteArray? {
            val entry = snapshots.firstOrNull { it.name == name } ?: return null
            val raw = dir.readFile(entry.fileName)?.use { it.readBytes() } ?: return null
            verifyHash(entry.fileName, raw, entry.encSha256)      // on-disk bytes, before decrypt
            val b = readBlob(raw.inputStream(), cipher)
            verifyHash(entry.fileName, b, entry.sha256)           // plaintext, after decrypt
            return b
        }
        bytesOf(SNAP_PLACES)?.let {
            backupDao.restorePlaces(
                decodeLines(
                    it,
                    net.extrawdw.apps.locationhistory.data.db.PlaceEntity.serializer()
                )
            )
        }
        bytesOf(SNAP_GEOFENCES)?.let {
            backupDao.restoreGeofences(
                decodeLines(
                    it,
                    net.extrawdw.apps.locationhistory.data.db.GeofenceEntity.serializer()
                )
            )
        }
        bytesOf(SNAP_STATE_EXAMPLES)?.let {
            backupDao.restoreStateExamples(
                decodeLines(
                    it,
                    net.extrawdw.apps.locationhistory.data.db.StateTrainingExampleEntity.serializer()
                )
            )
        }
        bytesOf(SNAP_TRANSPORT_EXAMPLES)?.let {
            backupDao.restoreTransportExamples(
                decodeLines(
                    it,
                    net.extrawdw.apps.locationhistory.data.db.TransportTrainingExampleEntity.serializer()
                )
            )
        }
    }

    private suspend fun restoreSettingsAndModels(
        root: SafDir, snapshots: List<SnapshotEntry>, cipher: BackupCrypto.PartitionCipher,
    ) {
        val dir = root.childDir(SNAPSHOT_DIR)
        fun bytesOf(name: String): ByteArray? {
            val entry = snapshots.firstOrNull { it.name == name } ?: return null
            val raw = dir.readFile(entry.fileName)?.use { it.readBytes() } ?: return null
            verifyHash(entry.fileName, raw, entry.encSha256)
            return readBlob(raw.inputStream(), cipher)
        }
        bytesOf(SNAP_SETTINGS)?.let { raw ->
            val s = runCatching {
                json.decodeFromString(
                    BackupSettings.serializer(),
                    raw.decodeToString()
                )
            }.getOrNull()
            s?.powerProfile?.let { name ->
                runCatching { PowerProfile.valueOf(name) }.getOrNull()
                    ?.let { settingsRepository.setPowerProfile(it) }
            }
        }
        bytesOf(SNAP_STATE_CKPT)?.let { modelStore.stateCheckpoint.writeBytes(it) }
        bytesOf(SNAP_TRANSPORT_CKPT)?.let { modelStore.transportCheckpoint.writeBytes(it) }
        modelStore.reload()
    }

    private inline fun <reified T> snapshotLines(
        dir: SafDir, material: Material, name: String, rows: List<T>, previous: SnapshotEntry?,
    ): SnapshotEntry {
        val (bytes, count) = encodeLines(rows)
        return snapshotBlob(dir, material, name, bytes, count, previous)
    }

    private fun snapshotBlob(
        dir: SafDir,
        material: Material,
        name: String,
        bytes: ByteArray,
        rowCount: Int,
        previous: SnapshotEntry?,
    ): SnapshotEntry {
        val disk = blobBytes(material, bytes)
        val encHash = sha256(disk)
        // Unchanged since the last committed backup -> reuse the existing file (most snapshots, every run).
        if (previous != null && previous.encSha256 == encHash && dir.exists(previous.fileName)) return previous
        val fileName = contentName(name, "gz", disk, material)
        dir.writeFile(fileName, "application/octet-stream") { out -> out.write(disk) }
        return SnapshotEntry(name, fileName, rowCount, sha256(bytes), encHash)
    }

    // -- Manifest + pruning -------------------------------------------------------------------

    /**
     * Write the encrypted inventory (content-addressed, additive) and then — as the single atomic
     * commit — the fixed-name public manifest. Returns the inventory file name so the caller can
     * prune superseded inventories afterward. Never overwrites the inventory the *current* manifest
     * still references: if the new inventory is byte-identical to the committed one, its file is reused.
     */
    private fun writeManifest(
        root: SafDir, material: Material, partitions: List<PartitionEntry>,
        snapshots: List<SnapshotEntry>, nowMs: Long, previousInventory: InventoryRef?,
    ): String {
        val inventory = BackupInventory(
            partitions = partitions.sortedWith(compareBy({ it.stream }, { it.weekStart })),
            snapshots = snapshots,
        )
        val invDisk = blobBytes(
            material,
            json.encodeToString(BackupInventory.serializer(), inventory).encodeToByteArray()
        )
        val invHash = sha256(invDisk)
        val invName =
            if (previousInventory != null && previousInventory.sha256 == invHash && root.exists(
                    previousInventory.fileName
                )
            ) {
                previousInventory.fileName // identical inventory already committed -> reuse, don't touch it
            } else {
                val name = contentName(INVENTORY_BASE, "json.gz", invDisk, material)
                root.writeFile(name, "application/octet-stream") { it.write(invDisk) }
                name
            }

        val manifest = BackupManifest(
            formatVersion = Constants.BACKUP_FORMAT_VERSION,
            schemaVersion = AppDatabase.SCHEMA_VERSION,
            createdAtMs = nowMs,
            crypto = material.header,
            inventory = InventoryRef(invName, invHash),
        )
        val checksummed = manifest.copy(checksum = manifestChecksum(manifest))
        // THE commit point: overwriting the fixed-name manifest atomically swaps to the new backup.
        val bytes =
            json.encodeToString(BackupManifest.serializer(), checksummed).encodeToByteArray()
        root.writeFile(MANIFEST, "application/json") { it.write(bytes) }
        return invName
    }

    /**
     * Delete files the just-committed manifest no longer references — superseded generations of
     * changed weeks/snapshots, stale inventories, and any duplicate-name copies. Runs AFTER the
     * manifest commit, so an interruption here only leaves harmless orphans (cleaned next run).
     */
    private fun pruneOrphans(
        root: SafDir,
        partitions: List<PartitionEntry>,
        snapshots: List<SnapshotEntry>,
        invName: String,
    ) {
        val keep = partitions.groupBy({ it.stream }, { it.fileName })
        for (stream in listOf(STREAM_SAMPLES, STREAM_VISITS, STREAM_TRIPS)) {
            val dir = root.childDir(stream)
            val keepSet = keep[stream]?.toSet() ?: emptySet()
            dir.fileNames().filterNot { it in keepSet }.forEach { dir.deleteFile(it) }
        }
        val snapDir = root.childDir(SNAPSHOT_DIR)
        val keepSnap = snapshots.map { it.fileName }.toSet()
        snapDir.fileNames().filterNot { it in keepSnap }.forEach { snapDir.deleteFile(it) }
        // Root: drop every superseded inventory file (manifest.json and the stream/snapshot subdirs
        // don't start with the inventory prefix, so they're untouched).
        root.fileNames().filter { it != invName && it.startsWith("$INVENTORY_BASE.") }
            .forEach { root.deleteFile(it) }
    }

    // -- Serialization / framing helpers ------------------------------------------------------

    private inline fun <reified T> encodeLines(rows: List<T>): Pair<ByteArray, Int> {
        val sb = StringBuilder()
        for (r in rows) sb.append(json.encodeToString(r)).append('\n')
        return sb.toString().encodeToByteArray() to rows.size
    }

    private fun <T> decodeLines(
        bytes: ByteArray,
        serializer: kotlinx.serialization.KSerializer<T>
    ): List<T> {
        var skipped = 0
        val rows = bytes.decodeToString().lineSequence()
            .filter { it.isNotBlank() }
            // Skip (don't abort on) a single undecodable row, so one bad/old line can't sink the whole
            // restore. Forward-compat safety net alongside per-field defaults + ignoreUnknownKeys.
            .mapNotNull { line ->
                runCatching {
                    json.decodeFromString(
                        serializer,
                        line
                    )
                }.getOrElse { skipped++; null }
            }
            .toList()
        if (skipped > 0) AppLog.w(TAG, "restore: skipped $skipped undecodable row(s)")
        return rows
    }

    /** gzip then (optionally) encrypt: [enc([gzip(plaintext)])]. */
    private fun writeBlob(out: java.io.OutputStream, material: Material, plaintext: ByteArray) {
        val enc = material.cipher.wrap(out)
        GZIPOutputStream(enc).use { it.write(plaintext) }
    }

    /** [writeBlob]'s output materialized in memory, so its on-disk hash can be recorded. */
    private fun blobBytes(material: Material, plaintext: ByteArray): ByteArray =
        ByteArrayOutputStream().also { writeBlob(it, material, plaintext) }.toByteArray()

    /** Inverse of [writeBlob]: decrypt then gunzip back to the plaintext serialized bytes. */
    private fun readBlob(
        input: java.io.InputStream,
        cipher: BackupCrypto.PartitionCipher
    ): ByteArray {
        val dec = cipher.unwrap(input)
        return GZIPInputStream(dec).use { gz ->
            val buffer = ByteArrayOutputStream()
            gz.copyTo(buffer)
            buffer.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Content-addressed file name: `<base>.<16-hex-of-on-disk-hash>.<ext>[.enc]`. Embedding the hash
     * makes the file immutable (distinct content -> distinct name), which is what gives the backup its
     * write-once / crash-atomic property and makes duplicate names impossible.
     */
    private fun contentName(
        base: String,
        ext: String,
        disk: ByteArray,
        material: Material
    ): String =
        "$base.${sha256Hex(disk).take(16)}.$ext" + if (material.cipher.encrypted) ".enc" else ""

    private fun verifyHash(fileName: String, bytes: ByteArray, expected: String) {
        val actual = sha256(bytes)
        if (actual != expected) error("backup file '$fileName' failed integrity check (hash mismatch)")
    }

    companion object {
        const val STREAM_SAMPLES = "samples"
        const val STREAM_VISITS = "visits"
        const val STREAM_TRIPS = "trips"

        private const val MANIFEST = "manifest.json"
        private const val INVENTORY_BASE = "inventory"
        private const val SNAPSHOT_DIR = "snapshot"
        private const val SNAP_PLACES = "places"
        private const val SNAP_GEOFENCES = "geofences"
        private const val SNAP_STATE_EXAMPLES = "state_examples"
        private const val SNAP_TRANSPORT_EXAMPLES = "transport_examples"
        private const val SNAP_SETTINGS = "settings"
        private const val SNAP_STATE_CKPT = "state_model_ckpt"
        private const val SNAP_TRANSPORT_CKPT = "transport_model_ckpt"
        private const val TAG = "BackupEngine"
    }
}
