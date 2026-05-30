package net.extrawdw.apps.locationhistory.backup

import kotlinx.serialization.Serializable
import net.extrawdw.apps.locationhistory.security.CryptoHeader

/**
 * The spine of a backup. Describes every file in the backup directory: its logical content hash
 * (sha-256 over the *uncompressed, unencrypted* serialized bytes, so it is deterministic and can be
 * recomputed from the DB during reclaim), row count, and which stream/week it covers. Restore and
 * incremental reconciliation are both driven entirely by this file.
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    /** Room schema version the partitions were serialized from; restore refuses a newer one. */
    val schemaVersion: Int,
    val createdAtMs: Long,
    val crypto: CryptoHeader,
    val partitions: List<PartitionEntry> = emptyList(),
    val snapshots: List<SnapshotEntry> = emptyList(),
)

/** A per-(stream, week) partition file holding append-mostly rows. */
@Serializable
data class PartitionEntry(
    val stream: String,
    val weekStart: Long,
    val weekKey: String,
    val fileName: String,
    val rowCount: Int,
    val sha256: String,
)

/** A whole-table or whole-blob snapshot rewritten on every backup (places, settings, models, …). */
@Serializable
data class SnapshotEntry(
    val name: String,
    val fileName: String,
    val rowCount: Int,
    val sha256: String,
)

/** Small key/value snapshot of restorable app settings. */
@Serializable
data class BackupSettings(
    val powerProfile: String? = null,
)
