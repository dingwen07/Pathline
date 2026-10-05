package net.extrawdw.apps.locationhistory.backup

import android.content.Context
import androidx.room3.withWriteTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.TimeBuckets
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.BackupDao
import net.extrawdw.apps.locationhistory.data.db.BackupDirtyPartitionEntity
import net.extrawdw.apps.locationhistory.data.enrich.DeviceStateCollector
import net.extrawdw.apps.locationhistory.data.repo.PowerProfile
import net.extrawdw.apps.locationhistory.data.repo.LegacyPlaceCoordinateManager
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.security.BackupCrypto
import net.extrawdw.apps.locationhistory.security.CryptoHeader
import net.extrawdw.apps.locationhistory.security.MapsApiKeyVault
import net.extrawdw.apps.locationhistory.service.Perf
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a backup run. */
data class BackupReport(
    val partitionsWritten: Int,
    val partitionsFailed: Int,
    val totalPartitions: Int,
    val cleanupPending: Boolean = false,
)

/** Outcome of a restore. */
data class RestoreReport(val partitionsRestored: Int, val rowsRestored: Int, val recoveredEarlierGeneration: Boolean = false)

/**
 * Reads/writes the structured backup described by [BackupManifest].
 *
 * Two write modes:
 *  - **incremental** ([runIncremental]) — reads the dirty-partition set the triggers maintain and
 *    re-emits only those (stream, week) files, merging into the existing inventory; the claimed
 *    keys are cleared only after the manifest commit, so an interrupted run loses no markers. The
 *    standard periodic path; only the latest week(s) change for an active recorder.
 *  - **full** ([runFull]) — re-emits every populated week. Used for the one-time database dump and
 *    for reclaim reconciliation when a SAF grant was lost.
 *
 * Immutable blobs are verified before publishing a numbered manifest. Incremental writes keep
 * the previous manifest until publication; full backups first archive existing files, except
 * encryption changes which delete them. Cleanup retains only the newly committed file set.
 * SAF does not provide atomic replacement or a remote upload durability guarantee.
 *
 * The manifest is split in two: a minimal public manifest (versions + crypto header +
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
    private val mapsApiKeyVault: MapsApiKeyVault,
    private val legacyPlaceCoordinates: LegacyPlaceCoordinateManager,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Crypto material for one run: the manifest header plus a cipher bound to its DEK. */
    class Material(val header: CryptoHeader, dek: ByteArray?) {
        val cipher = BackupCrypto.PartitionCipher(dek)
    }

    class FullBackupRequired(cause: Throwable) : Exception(cause)

    /** Resolves generation manifests as well as legacy manifest.json backups. */
    suspend fun readManifest(root: SafDir): BackupManifest? = BackupArchive.latest(root)

    suspend fun hasBackupFiles(root: SafDir): Boolean = BackupArchive.hasBackupFiles(root)

    suspend fun deleteBeforeEncryptionChange(root: SafDir, reporter: BackupReporter) {
        BackupArchive.deleteBeforeEncryptionChange(root) { logBackup(reporter, it) }
    }

    private suspend fun readInventory(
        root: SafDir, manifest: BackupManifest, cipher: BackupCrypto.PartitionCipher,
    ): BackupInventory = BackupArchive.inventory(root, manifest, cipher)

    suspend fun runIncremental(
        root: SafDir,
        material: Material,
        nowMs: Long,
        reporter: BackupReporter = BackupReporter.None,
    ): BackupReport = Perf.trace("backup_incremental") { span ->
        val existing = readManifest(root)
        val previous = try {
            if (existing == null || existing.crypto != material.header) {
                throw BackupContentException("Backup baseline needs rebuilding")
            }
            readInventory(root, existing, material.cipher)
        } catch (e: BackupContentException) {
            throw FullBackupRequired(e)
        }
        val prevPartitions = previous.partitions.associateBy { it.stream + "/" + it.weekStart }
        val prevSnapshots = previous.snapshots.associateBy { it.name }

        val merged = LinkedHashMap<String, PartitionEntry>()
        previous.partitions.forEach { merged[it.stream + "/" + it.weekStart] = it }

        // Read the dirty set WITHOUT consuming it: the claimed keys are deleted only after the
        // manifest commit below, so a run that dies anywhere before that point (emit error, SAF
        // failure, process kill, WorkManager cancellation) leaves every marker in place for the
        // next run. Markers added by the recorder after this read are new keys the post-commit
        // delete never matches.
        val claimed = backupDao.allDirty()
        val claimedSet = claimed.toSet()
        val repair = BackupArchive.partitionsToRepair(root, previous.partitions.filter {
            BackupDirtyPartitionEntity(it.stream, it.weekStart) !in claimedSet
        }).map { BackupDirtyPartitionEntity(it.stream, it.weekStart) }
        val work = (claimed + repair).distinct()
        val total = (work.size + 1).coerceAtLeast(1)
        reporter.log("Incremental backup: ${claimed.size} changed partition(s), ${repair.size} partition(s) to repair")
        var written = 0
        var failed = 0
        var done = 0
        val emitted = ArrayList<BackupDirtyPartitionEntity>(claimed.size)
        for (dirty in work) {
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
                if (dirty in claimedSet) emitted += dirty
                written++
                reporter.log("Backed up $label (${entry?.rowCount ?: 0} rows)")
            } catch (e: CancellationException) {
                throw e // cooperative cancellation must abort the run; markers are still intact
            } catch (t: Throwable) {
                AppLog.w(TAG, "partition $label failed: ${t.message}")
                failed++ // marker was never deleted -> the partition is retried next run
                reporter.log("FAILED $label: ${t.message}")
            }
            reporter.progress(++done / total.toFloat())
        }

        reporter.log("Writing snapshots…")
        val snapshots = writeSnapshots(root, material, prevSnapshots)
        val partitions = merged.values.toList()
        val committed = writeManifest(root, material, partitions, snapshots, nowMs, existing, previous)
        // The manifest commit succeeded: only now retire the markers of partitions this run
        // actually emitted (a failed emit keeps its marker by exclusion).
        backupDao.clearDirtySet(emitted)
        val cleanupPending = cleanup(root, committed, reporter)
        if (cleanupPending) reporter.log("Backup saved; cleanup is pending and will retry")
        reporter.progress(1f)
        AppLog.i(TAG, "incremental backup: wrote=$written failed=$failed total=${partitions.size}")
        BackupReport(written, failed, partitions.size, cleanupPending).also {
            span.metric("partitions_written", it.partitionsWritten.toLong())
            span.metric("partitions_failed", it.partitionsFailed.toLong())
            span.metric("partitions_total", it.totalPartitions.toLong())
        }
    }

    suspend fun runFull(
        root: SafDir, material: Material, nowMs: Long, clearDirtyAfter: Boolean,
        reporter: BackupReporter = BackupReporter.None,
        archiveExisting: Boolean = true,
    ): BackupReport = Perf.trace("backup_full") { span ->
        // Snapshot the dirty set up front when this run is meant to clear it: every week populated
        // at this point is re-read by the emits below, while markers added during the (potentially
        // minutes-long) run are new keys the post-commit delete never matches.
        val dirtyAtStart = if (clearDirtyAfter) backupDao.allDirty() else emptyList()
        val weeksByStream = mapOf(
            STREAM_SAMPLES to backupDao.sampleWeeks(),
            STREAM_VISITS to backupDao.visitWeeks(),
            STREAM_TRIPS to backupDao.tripWeeks(),
        )
        // Encryption changes have already deleted the active files before changing local keys.
        if (archiveExisting) BackupArchive.archiveBeforeFull(root, nowMs) { logBackup(reporter, it) }
        val total = (weeksByStream.values.sumOf { it.size } + 1).coerceAtLeast(1)
        reporter.log("Full backup: $total partition group(s)")
        val entries = ArrayList<PartitionEntry>()
        val failedKeys = HashSet<String>()
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
                        previous = null
                    )?.let {
                        entries.add(it)
                        reporter.log("Backed up $stream/${it.weekKey} (${it.rowCount} rows)")
                    }
                } catch (e: CancellationException) {
                    throw e // cooperative cancellation must abort the run; markers are still intact
                } catch (t: Throwable) {
                    val label = "$stream/${TimeBuckets.weekKey(week)}"
                    AppLog.w(TAG, "full: $label failed: ${t.message}")
                    failedKeys += "$stream/$week"
                    failed++
                    reporter.log("FAILED $label: ${t.message}")
                }
                reporter.progress(++done / total.toFloat())
            }
        }
        require(failed == 0) {
            if (archiveExisting) "Full backup could not save and verify every partition; the previous backup was preserved"
            else "Full backup could not save and verify every partition; the previous backup was deleted for the encryption change"
        }
        reporter.log("Writing snapshots…")
        val snapshots = writeSnapshots(root, material, emptyMap())
        val committed = writeManifest(root, material, entries, snapshots, nowMs, null, BackupInventory())
        // The manifest commit succeeded: retire only markers present at run start whose week was
        // emitted; a failed week keeps its marker so the next incremental retries it.
        if (clearDirtyAfter) {
            backupDao.clearDirtySet(dirtyAtStart.filterNot { it.stream + "/" + it.weekStart in failedKeys })
        }
        val cleanupPending = cleanup(root, committed, reporter)
        if (cleanupPending) reporter.log("Backup saved; cleanup is pending and will retry")
        reporter.progress(1f)
        AppLog.i(TAG, "full backup: wrote=${entries.size} failed=$failed")
        BackupReport(entries.size, failed, entries.size, cleanupPending).also {
            span.metric("partitions_written", it.partitionsWritten.toLong())
            span.metric("partitions_failed", it.partitionsFailed.toLong())
        }
    }

    private suspend fun cleanup(root: SafDir, committed: BackupArchive.Opened, reporter: BackupReporter): Boolean {
        val result = BackupArchive.cleanup(root, committed) { logBackup(reporter, it) }
        result.detail?.let {
            reporter.log(it)
            if (result.error != null) AppLog.e(TAG, it, result.error) else AppLog.i(TAG, it)
        }
        return !result.complete
    }

    private fun logBackup(reporter: BackupReporter, message: String) {
        reporter.log(message)
        // Managed reporters already persist their messages; unattended runs need the same detail.
        if (reporter === BackupReporter.None) AppLog.i(TAG, message)
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
    ): RestoreReport = Perf.trace("backup_restore") { span ->
        val recovery = BackupArchive.recover(root, password, prfSecret, AppDatabase.SCHEMA_VERSION)
        val inventory = recovery.opened.inventory
        val cipher = recovery.cipher
        if (recovery.recovered) reporter.log("Recovered an earlier complete backup generation after the latest one failed validation")
        reporter.log("Restoring ${inventory.partitions.size} partition(s)…")

        val total = (inventory.partitions.size + 1).coerceAtLeast(1)
        var rows = 0
        var done = 0
        db.withWriteTransaction {
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
            // A retained old partition can sit under a newer incremental manifest. Validate each
            // candidate tuple by its own provenance rather than trusting the manifest version.
            backupDao.classifyIdentityFrameLegacyCandidates()
            backupDao.clearUntrustedCandidates()
            // Repair trip->visit links that the backup itself carried broken (older exports, or data
            // imported from a previous app version): detach any trip endpoint whose visit is absent so
            // the restored DB is self-consistent rather than reproducing the dangling references.
            backupDao.detachDanglingTripVisits()
            // Likewise drop polymorphic tag links / annotations whose target row wasn't imported.
            backupDao.purgeDanglingAnnotationsAndTags()
            // The REPLACE inserts re-fire the dirty triggers; clear so we don't immediately
            // re-upload the whole history we just pulled down.
            backupDao.clearAllDirty()
        }
        restoreSettings(root, inventory.snapshots, cipher)
        // Old backups intentionally decode place provenance as UNKNOWN. Re-enable only rows that
        // the frozen on-device classifier proves are exact identity, journaling the state change.
        legacyPlaceCoordinates.classifySafeRows()
        reporter.progress(1f)
        AppLog.i(TAG, "restore complete: partitions=${inventory.partitions.size} rows=$rows")
        RestoreReport(inventory.partitions.size, rows, recovery.recovered).also {
            span.metric("partitions_restored", it.partitionsRestored.toLong())
            span.metric("rows_restored", it.rowsRestored.toLong())
        }
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
        val weekKey = TimeBuckets.weekKey(weekStart)
        val blob = BackupArchive.storeBlob(root.backupDir(stream), weekKey, "jsonl.gz", bytes, material.cipher,
            previous?.let { BackupArchive.Blob(it.fileName, it.sha256, it.encSha256) })
        return PartitionEntry(stream, weekStart, weekKey, blob.fileName, rowCount, blob.plainHash, blob.diskHash)
    }

    private suspend fun restorePartition(
        root: SafDir, entry: PartitionEntry, cipher: BackupCrypto.PartitionCipher,
    ): Int {
        val dir = root.backupDirOrNull(entry.stream) ?: error("missing backup stream")
        val raw = dir.readVerified(entry.fileName, entry.encSha256)
            ?: error("missing partition file ${entry.fileName}")
        verifyHash(entry.fileName, raw, entry.encSha256)          // on-disk bytes, before decrypt
        val bytes = readBlob(raw.inputStream(), cipher)
        verifyHash(entry.fileName, bytes, entry.sha256)           // plaintext, after decrypt
        return when (entry.stream) {
            STREAM_SAMPLES -> decodeLines(
                bytes,
                net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity.serializer()
            )
                // Older recordings stored Android's location-redaction placeholder as a real BSSID
                // (the collector read a redacted WifiInfo). It carries zero information, so
                // normalize it to null on the way in — keeps Wi-Fi-presence stats clean.
                .map { sample ->
                    if (sample.wifiBssid == DeviceStateCollector.REDACTED_BSSID) {
                        sample.copy(wifiBssid = null)
                    } else {
                        sample
                    }
                }
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
        val dir = root.backupDir(SNAPSHOT_DIR)
        val out = ArrayList<SnapshotEntry>()

        out += snapshotLines(dir, material, SNAP_DELETED_RANGES,
            backupDao.allDeletedRanges(), previous[SNAP_DELETED_RANGES])

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
            SNAP_PLACE_COORDINATE_REPAIRS,
            backupDao.allPlaceCoordinateRepairs(),
            previous[SNAP_PLACE_COORDINATE_REPAIRS],
        )
        out += snapshotLines(
            dir,
            material,
            SNAP_GEOFENCES,
            backupDao.allGeofences(),
            previous[SNAP_GEOFENCES]
        )
        // Tags, the polymorphic tag<->target join, annotations (notes + memories), and concepts
        // with their member edge. Whole-table snapshots like places — small and not time-bucketed.
        out += snapshotLines(dir, material, SNAP_TAGS, backupDao.allTags(), previous[SNAP_TAGS])
        out += snapshotLines(
            dir,
            material,
            SNAP_ENTITY_TAGS,
            backupDao.allEntityTags(),
            previous[SNAP_ENTITY_TAGS]
        )
        out += snapshotLines(
            dir,
            material,
            SNAP_ANNOTATIONS,
            backupDao.allAnnotations(),
            previous[SNAP_ANNOTATIONS]
        )
        out += snapshotLines(
            dir,
            material,
            SNAP_CONCEPTS,
            backupDao.allConcepts(),
            previous[SNAP_CONCEPTS]
        )
        out += snapshotLines(
            dir,
            material,
            SNAP_CONCEPT_MEMBERS,
            backupDao.allConceptMembers(),
            previous[SNAP_CONCEPT_MEMBERS]
        )

        // App settings
        val currentSettings = settingsRepository.settings.first()
        val mapsPlatform = if (
            currentSettings.includeMapsPlatformInBackup && material.cipher.encrypted
        ) {
            BackupMapsPlatformConfig(
                apiKey = mapsApiKeyVault.apiKey(),
                googleCloudProjectId = currentSettings.googleCloudProjectId,
                automaticNearbyDailyLimit = currentSettings.automaticNearbyDailyLimit,
            )
        } else null
        val settings = BackupSettings(
            powerProfile = currentSettings.powerProfile.name,
            mapsPlatform = mapsPlatform,
        )
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
        return out
    }

    private suspend fun restoreSnapshots(
        root: SafDir, snapshots: List<SnapshotEntry>, cipher: BackupCrypto.PartitionCipher,
    ) {
        if (snapshots.isEmpty()) return
        val dir = root.backupDirOrNull(SNAPSHOT_DIR) ?: error("missing backup snapshots")
        suspend fun bytesOf(name: String): ByteArray? {
            val entry = snapshots.firstOrNull { it.name == name } ?: return null
            val raw = dir.readVerified(entry.fileName, entry.encSha256) ?: error("missing backup snapshot")
            verifyHash(entry.fileName, raw, entry.encSha256)      // on-disk bytes, before decrypt
            val b = readBlob(raw.inputStream(), cipher)
            verifyHash(entry.fileName, b, entry.sha256)           // plaintext, after decrypt
            return b
        }
        bytesOf(SNAP_DELETED_RANGES)?.let {
            backupDao.restoreDeletedRanges(decodeLines(it,
                net.extrawdw.apps.locationhistory.data.db.DeletedTimeRangeEntity.serializer()))
        }
        bytesOf(SNAP_PLACES)?.let {
            backupDao.restorePlaces(
                decodeLines(
                    it,
                    net.extrawdw.apps.locationhistory.data.db.PlaceEntity.serializer()
                )
            )
        }
        bytesOf(SNAP_PLACE_COORDINATE_REPAIRS)?.let {
            backupDao.restorePlaceCoordinateRepairs(
                decodeLines(
                    it,
                    net.extrawdw.apps.locationhistory.data.db.PlaceCoordinateRepairEntity.serializer(),
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
        // Training-example and model-checkpoint snapshots in older backups are deliberately not
        // restored: the on-device training pipeline was removed, so those entries are simply left
        // unread (and pruned from the inventory by the next backup run).
        // Tags / entity_tags / annotations are absent from pre-v2 backups -> bytesOf returns null and
        // these tables simply stay empty (forward-compat). Restore tags before their links.
        bytesOf(SNAP_TAGS)?.let {
            backupDao.restoreTags(
                decodeLines(it, net.extrawdw.apps.locationhistory.data.db.TagEntity.serializer())
            )
        }
        bytesOf(SNAP_ENTITY_TAGS)?.let {
            backupDao.restoreEntityTags(
                decodeLines(
                    it,
                    net.extrawdw.apps.locationhistory.data.db.EntityTagEntity.serializer()
                )
            )
        }
        bytesOf(SNAP_ANNOTATIONS)?.let {
            backupDao.restoreAnnotations(
                decodeLines(
                    it,
                    net.extrawdw.apps.locationhistory.data.db.AnnotationEntity.serializer()
                )
            )
        }
        // Concepts (absent from older backups -> tables stay empty). Concepts before their members.
        bytesOf(SNAP_CONCEPTS)?.let {
            backupDao.restoreConcepts(
                decodeLines(
                    it,
                    net.extrawdw.apps.locationhistory.data.db.ConceptEntity.serializer()
                )
            )
        }
        bytesOf(SNAP_CONCEPT_MEMBERS)?.let {
            backupDao.restoreConceptMembers(
                decodeLines(
                    it,
                    net.extrawdw.apps.locationhistory.data.db.ConceptMemberEntity.serializer()
                )
            )
        }
    }

    private suspend fun restoreSettings(
        root: SafDir, snapshots: List<SnapshotEntry>, cipher: BackupCrypto.PartitionCipher,
    ) {
        if (snapshots.isEmpty()) return
        val dir = root.backupDirOrNull(SNAPSHOT_DIR) ?: error("missing backup snapshots")
        suspend fun bytesOf(name: String): ByteArray? {
            val entry = snapshots.firstOrNull { it.name == name } ?: return null
            val raw = dir.readVerified(entry.fileName, entry.encSha256) ?: error("missing backup snapshot")
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
            // A plaintext backup can never legitimately contain a key produced by Pathline. Keep
            // that invariant on restore too, even if a hand-edited/foreign snapshot adds the field.
            if (cipher.encrypted) s?.mapsPlatform?.let { maps ->
                maps.apiKey?.let { runCatching { mapsApiKeyVault.store(it) } }
                settingsRepository.setGoogleCloudProjectId(maps.googleCloudProjectId)
                runCatching {
                    settingsRepository.setAutomaticNearbyDailyLimit(
                        maps.automaticNearbyDailyLimit,
                    )
                }
                settingsRepository.setIncludeMapsPlatformInBackup(true)
            }
        }
    }

    private suspend inline fun <reified T> snapshotLines(
        dir: SafDir, material: Material, name: String, rows: List<T>, previous: SnapshotEntry?,
    ): SnapshotEntry {
        val (bytes, count) = encodeLines(rows)
        return snapshotBlob(dir, material, name, bytes, count, previous)
    }

    private suspend fun snapshotBlob(
        dir: SafDir,
        material: Material,
        name: String,
        bytes: ByteArray,
        rowCount: Int,
        previous: SnapshotEntry?,
    ): SnapshotEntry {
        val blob = BackupArchive.storeBlob(dir, name, "gz", bytes, material.cipher,
            previous?.let { BackupArchive.Blob(it.fileName, it.sha256, it.encSha256) })
        return SnapshotEntry(name, blob.fileName, rowCount, blob.plainHash, blob.diskHash)
    }

    private suspend fun writeManifest(
        root: SafDir, material: Material, partitions: List<PartitionEntry>,
        snapshots: List<SnapshotEntry>, nowMs: Long, existing: BackupManifest?, previous: BackupInventory,
    ): BackupArchive.Opened {
        val inventory = BackupInventory(partitions.sortedWith(compareBy({ it.stream }, { it.weekStart })), snapshots)
        val manifest = BackupArchive.publish(root, material.header, material.cipher, inventory,
            AppDatabase.SCHEMA_VERSION, nowMs,
            existing?.takeIf { it.crypto == material.header }?.let { BackupArchive.Opened(it, previous) })
        return BackupArchive.Opened(manifest, inventory)
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

    private fun readBlob(input: java.io.InputStream, cipher: BackupCrypto.PartitionCipher): ByteArray =
        input.use { BackupArchive.decodeBlob(it.readBytes(), cipher) }

    private fun sha256(bytes: ByteArray): String = backupHash(bytes)

    private fun verifyHash(fileName: String, bytes: ByteArray, expected: String) {
        val actual = sha256(bytes)
        if (actual != expected) error("backup file '$fileName' failed integrity check (hash mismatch)")
    }

    companion object {
        const val STREAM_SAMPLES = "samples"
        const val STREAM_VISITS = "visits"
        const val STREAM_TRIPS = "trips"

        private const val SNAPSHOT_DIR = "snapshot"
        private const val SNAP_DELETED_RANGES = "deleted_time_ranges"
        private const val SNAP_PLACES = "places"
        private const val SNAP_PLACE_COORDINATE_REPAIRS = "place_coordinate_repairs"
        private const val SNAP_GEOFENCES = "geofences"
        private const val SNAP_TAGS = "tags"
        private const val SNAP_ENTITY_TAGS = "entity_tags"
        private const val SNAP_ANNOTATIONS = "annotations"
        private const val SNAP_CONCEPTS = "concepts"
        private const val SNAP_CONCEPT_MEMBERS = "concept_members"
        private const val SNAP_SETTINGS = "settings"
        private const val TAG = "BackupEngine"
    }
}
