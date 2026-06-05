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
    /** Sample weeks (Monday dayEpoch) emitted this run — used to drive incremental GPX export. */
    val sampleWeeksTouched: Set<Long> = emptySet(),
)

/** Outcome of a restore. */
data class RestoreReport(val partitionsRestored: Int, val rowsRestored: Int)

/**
 * Reads/writes the structured backup described by [BackupManifest].
 *
 * Two write modes:
 *  - **incremental** ([runIncremental]) — claims the dirty-partition set the triggers maintain and
 *    re-emits only those (stream, week) files, merging into the existing manifest. This is the
 *    standard periodic path; only the latest week(s) change for an active recorder.
 *  - **full** ([runFull]) — re-emits every populated week and rewrites the manifest from scratch,
 *    pruning orphan files. Used for the one-time database dump and for reclaim reconciliation when
 *    a SAF grant was lost and we must guarantee the destination matches the database.
 *
 * Content hashes in the manifest are computed over the *uncompressed, unencrypted* serialized bytes
 * so they're deterministic and can be recomputed from the DB; files on disk are gzip-then-encrypt.
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

    // -- Public API ---------------------------------------------------------------------------

    /** [root] is the directory that directly contains `manifest.json` (the resolved subdir). */
    suspend fun readManifest(root: SafDir): BackupManifest? {
        val bytes = root.readFile(MANIFEST)?.use { it.readBytes() } ?: return null
        return runCatching { json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString()) }
            .getOrNull()
    }

    suspend fun runIncremental(
        root: SafDir, material: Material, nowMs: Long, reporter: BackupReporter = BackupReporter.None,
    ): BackupReport {
        val existing = readManifest(root)
        val merged = LinkedHashMap<String, PartitionEntry>()
        existing?.partitions?.forEach { merged[it.stream + "/" + it.weekStart] = it }

        val claimed = backupDao.claimDirty()
        val total = (claimed.size + 1).coerceAtLeast(1)
        reporter.log("Incremental backup: ${claimed.size} changed partition(s)")
        var written = 0
        var failed = 0
        var done = 0
        val sampleWeeks = HashSet<Long>()
        for (dirty in claimed) {
            val key = dirty.stream + "/" + dirty.weekStart
            val label = dirty.stream + "/" + TimeBuckets.weekKey(dirty.weekStart)
            try {
                val entry = emitPartition(root, dirty.stream, dirty.weekStart, material)
                if (entry == null) merged.remove(key) else merged[key] = entry
                if (dirty.stream == STREAM_SAMPLES) sampleWeeks.add(dirty.weekStart)
                written++
                reporter.log("Backed up $label (${entry?.rowCount ?: 0} rows)")
            } catch (t: Throwable) {
                AppLog.w(TAG, "partition $label failed: ${t.message}")
                backupDao.markDirty(listOf(dirty)) // retry next run
                failed++
                reporter.log("FAILED $label: ${t.message}")
            }
            reporter.progress(++done / total.toFloat())
        }

        reporter.log("Writing snapshots…")
        val snapshots = writeSnapshots(root, material)
        writeManifest(root, material.header, merged.values.toList(), snapshots, nowMs)
        reporter.progress(1f)
        AppLog.i(TAG, "incremental backup: wrote=$written failed=$failed total=${merged.size}")
        return BackupReport(written, failed, merged.size, sampleWeeks)
    }

    suspend fun runFull(
        root: SafDir, material: Material, nowMs: Long, clearDirtyAfter: Boolean,
        reporter: BackupReporter = BackupReporter.None,
    ): BackupReport {
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
                    emitPartition(root, stream, week, material)?.let {
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
        val snapshots = writeSnapshots(root, material)
        writeManifest(root, material.header, entries, snapshots, nowMs)
        pruneOrphans(root, entries, snapshots)
        if (clearDirtyAfter) backupDao.clearAllDirty()
        reporter.progress(1f)
        AppLog.i(TAG, "full backup: wrote=${entries.size} failed=$failed")
        val sampleWeeks = entries.filter { it.stream == STREAM_SAMPLES }.map { it.weekStart }.toSet()
        return BackupReport(entries.size, failed, entries.size, sampleWeeks)
    }

    /**
     * Write GPX track files (open, unencrypted) under an `export/` subdir of [gpxRoot]. When
     * [weeks] is null, every populated sample week is exported; otherwise only those weeks.
     */
    suspend fun exportGpx(gpxRoot: SafDir, weeks: Collection<Long>?): Int {
        val dir = gpxRoot.childDir(Constants.EXPORT_DIR)
        val targetWeeks = weeks ?: backupDao.sampleWeeks()
        var count = 0
        for (week in targetWeeks) {
            val (startDay, endExclusive) = week to (week + 7)
            val samples = backupDao.samplesForDays(startDay, endExclusive).filter { it.includedInComputation }
            val name = TimeBuckets.weekKey(week) + ".gpx"
            if (samples.isEmpty()) { dir.deleteFile(name); continue }
            dir.writeFile(name, "application/gpx+xml") { out -> GpxExporter.write(samples, out) }
            count++
        }
        return count
    }

    /**
     * Restore the database **as-is** from the backup in [tree]. Wipes current recorded/derived data
     * and re-inserts every row with its original primary key so all relational links survive.
     */
    suspend fun restore(
        root: SafDir, password: CharArray?, prfSecret: ByteArray? = null,
        reporter: BackupReporter = BackupReporter.None,
    ): RestoreReport {
        val manifest = readManifest(root) ?: error("no backup found in the selected folder")
        require(manifest.schemaVersion <= AppDatabase.SCHEMA_VERSION) {
            "backup was made by a newer app version (schema ${manifest.schemaVersion}); update first"
        }
        val dek = BackupCrypto.openDek(manifest.crypto, password, prfSecret)
        val cipher = BackupCrypto.PartitionCipher(dek)
        reporter.log("Restoring ${manifest.partitions.size} partition(s)…")

        val total = (manifest.partitions.size + 1).coerceAtLeast(1)
        var rows = 0
        var done = 0
        db.withTransaction {
            backupDao.wipeForRestore()
            // Time-series partitions first (visits, trips, samples), then snapshots (places,
            // geofences, models) below. No FK constraints are enforced today, so order is not
            // load-bearing; if FKs are ever added, places must be restored before visits/trips.
            for (stream in listOf(STREAM_VISITS, STREAM_TRIPS, STREAM_SAMPLES)) {
                manifest.partitions.filter { it.stream == stream }.forEach { entry ->
                    rows += restorePartition(root, entry, cipher)
                    reporter.log("Restored ${entry.stream}/${entry.weekKey} (${entry.rowCount} rows)")
                    reporter.progress(++done / total.toFloat())
                }
            }
            restoreSnapshots(root, manifest, cipher)
            // The REPLACE inserts re-fire the dirty triggers; clear so we don't immediately
            // re-upload the whole history we just pulled down.
            backupDao.clearAllDirty()
        }
        restoreSettingsAndModels(root, manifest, cipher)
        reporter.progress(1f)
        AppLog.i(TAG, "restore complete: partitions=${manifest.partitions.size} rows=$rows")
        return RestoreReport(manifest.partitions.size, rows)
    }

    // -- Partition emit / restore -------------------------------------------------------------

    private suspend fun emitPartition(
        root: SafDir, stream: String, weekStart: Long, material: Material,
    ): PartitionEntry? {
        val endDay = weekStart + 7
        val (bytes, rowCount) = when (stream) {
            STREAM_SAMPLES -> encodeLines(backupDao.samplesForDays(weekStart, endDay))
            STREAM_VISITS -> encodeLines(backupDao.visitsForDays(weekStart, endDay))
            STREAM_TRIPS -> encodeLines(backupDao.tripsForDays(weekStart, endDay))
            else -> error("unknown stream $stream")
        }
        val dir = root.childDir(stream)
        val weekKey = TimeBuckets.weekKey(weekStart)
        val fileName = "$weekKey.jsonl.gz" + if (material.cipher.encrypted) ".enc" else ""
        if (rowCount == 0) { dir.deleteFile(fileName); return null }
        dir.writeFile(fileName, "application/octet-stream") { out -> writeBlob(out, material, bytes) }
        return PartitionEntry(stream, weekStart, weekKey, fileName, rowCount, sha256(bytes))
    }

    private suspend fun restorePartition(
        root: SafDir, entry: PartitionEntry, cipher: BackupCrypto.PartitionCipher,
    ): Int {
        val dir = root.childDir(entry.stream)
        val bytes = dir.readFile(entry.fileName)?.use { readBlob(it, cipher) }
            ?: error("missing partition file ${entry.fileName}")
        verifyHash(entry.fileName, bytes, entry.sha256)
        return when (entry.stream) {
            STREAM_SAMPLES -> decodeLines(bytes, net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity.serializer())
                .also { backupDao.restoreSamples(it) }.size
            STREAM_VISITS -> decodeLines(bytes, net.extrawdw.apps.locationhistory.data.db.VisitEntity.serializer())
                .also { backupDao.restoreVisits(it) }.size
            STREAM_TRIPS -> decodeLines(bytes, net.extrawdw.apps.locationhistory.data.db.TripEntity.serializer())
                .also { backupDao.restoreTrips(it) }.size
            else -> error("unknown stream ${entry.stream}")
        }
    }

    // -- Snapshots (small whole-table / whole-blob data, rewritten every run) -----------------

    private suspend fun writeSnapshots(root: SafDir, material: Material): List<SnapshotEntry> {
        val dir = root.childDir(SNAPSHOT_DIR)
        val out = ArrayList<SnapshotEntry>()

        out += snapshotLines(dir, material, SNAP_PLACES, backupDao.allPlaces())
        out += snapshotLines(dir, material, SNAP_GEOFENCES, backupDao.allGeofences())
        out += snapshotLines(dir, material, SNAP_STATE_EXAMPLES, backupDao.allStateExamples())
        out += snapshotLines(dir, material, SNAP_TRANSPORT_EXAMPLES, backupDao.allTransportExamples())

        // App settings
        val settings = BackupSettings(powerProfile = settingsRepository.settings.first().powerProfile.name)
        val settingsBytes = json.encodeToString(BackupSettings.serializer(), settings).encodeToByteArray()
        out += snapshotBlob(dir, material, SNAP_SETTINGS, settingsBytes, rowCount = 1)

        // ML checkpoints (raw bytes)
        modelStore.stateCheckpoint.takeIf { it.exists() }?.let {
            out += snapshotBlob(dir, material, SNAP_STATE_CKPT, it.readBytes(), rowCount = 1)
        }
        modelStore.transportCheckpoint.takeIf { it.exists() }?.let {
            out += snapshotBlob(dir, material, SNAP_TRANSPORT_CKPT, it.readBytes(), rowCount = 1)
        }
        return out
    }

    private suspend fun restoreSnapshots(
        root: SafDir, manifest: BackupManifest, cipher: BackupCrypto.PartitionCipher,
    ) {
        val dir = root.childDir(SNAPSHOT_DIR)
        fun bytesOf(name: String): ByteArray? {
            val entry = manifest.snapshots.firstOrNull { it.name == name } ?: return null
            val b = dir.readFile(entry.fileName)?.use { readBlob(it, cipher) } ?: return null
            verifyHash(entry.fileName, b, entry.sha256)
            return b
        }
        bytesOf(SNAP_PLACES)?.let { backupDao.restorePlaces(decodeLines(it, net.extrawdw.apps.locationhistory.data.db.PlaceEntity.serializer())) }
        bytesOf(SNAP_GEOFENCES)?.let { backupDao.restoreGeofences(decodeLines(it, net.extrawdw.apps.locationhistory.data.db.GeofenceEntity.serializer())) }
        bytesOf(SNAP_STATE_EXAMPLES)?.let { backupDao.restoreStateExamples(decodeLines(it, net.extrawdw.apps.locationhistory.data.db.StateTrainingExampleEntity.serializer())) }
        bytesOf(SNAP_TRANSPORT_EXAMPLES)?.let { backupDao.restoreTransportExamples(decodeLines(it, net.extrawdw.apps.locationhistory.data.db.TransportTrainingExampleEntity.serializer())) }
    }

    private suspend fun restoreSettingsAndModels(
        root: SafDir, manifest: BackupManifest, cipher: BackupCrypto.PartitionCipher,
    ) {
        val dir = root.childDir(SNAPSHOT_DIR)
        fun bytesOf(name: String): ByteArray? {
            val entry = manifest.snapshots.firstOrNull { it.name == name } ?: return null
            return dir.readFile(entry.fileName)?.use { readBlob(it, cipher) }
        }
        bytesOf(SNAP_SETTINGS)?.let { raw ->
            val s = runCatching { json.decodeFromString(BackupSettings.serializer(), raw.decodeToString()) }.getOrNull()
            s?.powerProfile?.let { name ->
                runCatching { PowerProfile.valueOf(name) }.getOrNull()?.let { settingsRepository.setPowerProfile(it) }
            }
        }
        bytesOf(SNAP_STATE_CKPT)?.let { modelStore.stateCheckpoint.writeBytes(it) }
        bytesOf(SNAP_TRANSPORT_CKPT)?.let { modelStore.transportCheckpoint.writeBytes(it) }
        modelStore.reload()
    }

    private inline fun <reified T> snapshotLines(
        dir: SafDir, material: Material, name: String, rows: List<T>,
    ): SnapshotEntry {
        val (bytes, count) = encodeLines(rows)
        return snapshotBlob(dir, material, name, bytes, count)
    }

    private fun snapshotBlob(
        dir: SafDir, material: Material, name: String, bytes: ByteArray, rowCount: Int,
    ): SnapshotEntry {
        val fileName = "$name.gz" + if (material.cipher.encrypted) ".enc" else ""
        dir.writeFile(fileName, "application/octet-stream") { out -> writeBlob(out, material, bytes) }
        return SnapshotEntry(name, fileName, rowCount, sha256(bytes))
    }

    // -- Manifest + pruning -------------------------------------------------------------------

    private fun writeManifest(
        root: SafDir, header: CryptoHeader, partitions: List<PartitionEntry>,
        snapshots: List<SnapshotEntry>, nowMs: Long,
    ) {
        val manifest = BackupManifest(
            formatVersion = Constants.BACKUP_FORMAT_VERSION,
            schemaVersion = AppDatabase.SCHEMA_VERSION,
            createdAtMs = nowMs,
            crypto = header,
            partitions = partitions.sortedWith(compareBy({ it.stream }, { it.weekStart })),
            snapshots = snapshots,
        )
        val bytes = json.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray()
        root.writeFile(MANIFEST, "application/json") { it.write(bytes) }
    }

    /** Delete partition + snapshot files that the new manifest no longer references. */
    private fun pruneOrphans(root: SafDir, entries: List<PartitionEntry>, snapshots: List<SnapshotEntry>) {
        val keep = entries.groupBy({ it.stream }, { it.fileName })
        for (stream in listOf(STREAM_SAMPLES, STREAM_VISITS, STREAM_TRIPS)) {
            val dir = root.childDir(stream)
            val keepSet = keep[stream]?.toSet() ?: emptySet()
            dir.fileNames().filterNot { it in keepSet }.forEach { dir.deleteFile(it) }
        }
        // Snapshot dir: keep only the files just written. This is what removes stale plaintext
        // snapshots (e.g. "places.gz") after switching the backup to encrypted ("places.gz.enc"),
        // and vice-versa.
        val snapDir = root.childDir(SNAPSHOT_DIR)
        val keepSnap = snapshots.map { it.fileName }.toSet()
        snapDir.fileNames().filterNot { it in keepSnap }.forEach { snapDir.deleteFile(it) }
    }

    // -- Serialization / framing helpers ------------------------------------------------------

    private inline fun <reified T> encodeLines(rows: List<T>): Pair<ByteArray, Int> {
        val sb = StringBuilder()
        for (r in rows) sb.append(json.encodeToString(r)).append('\n')
        return sb.toString().encodeToByteArray() to rows.size
    }

    private fun <T> decodeLines(bytes: ByteArray, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        bytes.decodeToString().lineSequence()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString(serializer, it) }
            .toList()

    /** gzip then (optionally) encrypt: [enc([gzip(plaintext)])]. */
    private fun writeBlob(out: java.io.OutputStream, material: Material, plaintext: ByteArray) {
        val enc = material.cipher.wrap(out)
        GZIPOutputStream(enc).use { it.write(plaintext) }
    }

    /** Inverse of [writeBlob]: decrypt then gunzip back to the plaintext serialized bytes. */
    private fun readBlob(input: java.io.InputStream, cipher: BackupCrypto.PartitionCipher): ByteArray {
        val dec = cipher.unwrap(input)
        return GZIPInputStream(dec).use { gz ->
            val buffer = ByteArrayOutputStream()
            gz.copyTo(buffer)
            buffer.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun verifyHash(fileName: String, bytes: ByteArray, expected: String) {
        val actual = sha256(bytes)
        if (actual != expected) error("backup file '$fileName' failed integrity check (hash mismatch)")
    }

    companion object {
        const val STREAM_SAMPLES = "samples"
        const val STREAM_VISITS = "visits"
        const val STREAM_TRIPS = "trips"

        private const val MANIFEST = "manifest.json"
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
