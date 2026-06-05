package net.extrawdw.apps.locationhistory.backup

import kotlinx.serialization.Serializable
import net.extrawdw.apps.locationhistory.security.CryptoHeader

/**
 * The public spine of a backup, written **unencrypted** as `manifest.json`. It exposes only the
 * minimum needed to bootstrap a restore on a fresh device:
 *  - format / schema versions, so an incompatible backup is rejected before anything is read;
 *  - the [crypto] header, whose key slots let the holder of a password/passkey recover the DEK;
 *  - a pointer to the encrypted [inventory] file plus its on-disk hash, so its integrity can be
 *    checked before it is decrypted.
 *
 * Everything that would leak the *shape* of the user's history (which weeks have data, how many
 * rows, content hashes, snapshot inventory) lives in the encrypted [BackupInventory] instead, so an
 * encrypted backup reveals nothing about its contents from the plaintext manifest.
 *
 * [checksum] is a sha-256 over this manifest's own canonical bytes (computed with the field blank).
 * It detects accidental corruption/truncation of the plaintext manifest. It is not a keyed MAC — the
 * cryptographically meaningful fields are self-authenticating regardless: the wrapped DEK in [crypto]
 * is AES-GCM sealed (a tampered slot fails to open), and the [inventory] is GCM-encrypted with its
 * on-disk hash recorded here, so any tampering downstream of the DEK is caught on restore.
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    /** Room schema version the partitions were serialized from; restore refuses a newer one. */
    val schemaVersion: Int,
    val createdAtMs: Long,
    val crypto: CryptoHeader,
    val inventory: InventoryRef,
    /** sha-256 over this manifest serialized with `checksum` blanked; see class doc. */
    val checksum: String = "",
)

/** Pointer from the public manifest to the encrypted inventory file. */
@Serializable
data class InventoryRef(
    val fileName: String,
    /** sha-256 of the on-disk (gzip-then-encrypt) inventory bytes; verified before decryption. */
    val sha256: String,
)

/**
 * The sensitive, **encrypted** half of the manifest. Lists every partition and snapshot file in the
 * backup. Restore and incremental reconciliation are both driven entirely by this inventory; it is
 * only readable once the DEK has been recovered from the public manifest's [BackupManifest.crypto].
 */
@Serializable
data class BackupInventory(
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
    /** sha-256 over the *uncompressed, unencrypted* serialized bytes (deterministic; recomputable). */
    val sha256: String,
    /** sha-256 of the on-disk file bytes (gzip[+enc]); verified before the file is decrypted. */
    val encSha256: String = "",
)

/** A whole-table or whole-blob snapshot rewritten on every backup (places, settings, models, ...). */
@Serializable
data class SnapshotEntry(
    val name: String,
    val fileName: String,
    val rowCount: Int,
    /** sha-256 over the *uncompressed, unencrypted* serialized bytes (deterministic; recomputable). */
    val sha256: String,
    /** sha-256 of the on-disk file bytes (gzip[+enc]); verified before the file is decrypted. */
    val encSha256: String = "",
)

/** Small key/value snapshot of restorable app settings. */
@Serializable
data class BackupSettings(
    val powerProfile: String? = null,
)
