package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.NetworkTransport
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.TransportMode

/**
 * The append-only fact table: one row per location sample, never deleted. Captures every piece of
 * metadata the platform exposes plus enriched device-state context. Indexed by [dayEpoch] so a
 * day's worth of data is retrieved with an integer range scan even across years of history.
 *
 * This table is intentionally excluded from cloud auto-backup (see backup_rules.xml); it is
 * exported in compact monthly partitions instead.
 */
@Serializable
@Entity(
    tableName = "location_samples",
    indices = [Index("dayEpoch"), Index("timestampMs")],
)
data class LocationSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val timestampMs: Long,
    val dayEpoch: Long,

    // Core fix from the Location API
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val verticalAccuracyMeters: Float?,
    val bearing: Float?,
    val bearingAccuracyDegrees: Float?,
    val speed: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val provider: String?,
    val isMock: Boolean,
    val elapsedRealtimeNanos: Long,
    val satelliteCount: Int?,

    // Enriched device-state metadata
    val batteryPct: Int?,
    val isCharging: Boolean?,
    val networkTransport: NetworkTransport?,
    val networkTypeName: String?,
    val cellSignalDbm: Int?,
    val hasCellService: Boolean?,
    val wifiSsid: String?,
    val wifiBssid: String?,
    val screenOn: Boolean?,

    // Activity Recognition + classifier output
    val arActivity: String?,
    val arConfidence: Int?,
    val devicePhysicalState: DevicePhysicalState,
    val devicePhysicalStateConfidence: Float,

    // IMU + barometer burst summary, batch-granular: one burst is collected per delivered
    // location batch and stamped on every sample in it (see MotionSensorReader.burstSummary).
    // Nullable + defaulted so rows from older recordings/backups decode unchanged.
    val motionVariance: Float? = null,
    val stepCadenceHz: Float? = null,
    val gravityAngleDeltaDeg: Float? = null,
    val pressureHpa: Float? = null,

    /** All samples are stored; this flag (with [exclusionReason]) excludes a sample from
     *  computation — e.g. mock locations or implausibly large accuracy — without deleting it. */
    val includedInComputation: Boolean = true,
    val exclusionReason: String? = null,
)

/** A place in the local database. Confirmed places are treated as ground truth for matching. */
@Serializable
@Entity(
    tableName = "places",
    indices = [Index("latitude"), Index("longitude")],
)
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    /** The single primary Google place type (or first of the type list); user-facing category. */
    val category: String?,
    /** Comma-joined full Google place-type list (Google's `types`), or null. [category] is the primary one. */
    val types: String? = null,
    val source: PlaceSource,
    val googlePlaceId: String?,
    val address: String?,
    val confirmed: Boolean,
    val createdAtMs: Long,
    /** When true the user has pinned the center/radius — the app must not auto-update them. */
    val fixed: Boolean = false,
    /**
     * The place's *original* center at creation (Google's coordinates for a MAPS place, or the
     * founding visit's centroid otherwise). It never changes, and is folded into the weighted
     * center/radius recompute as a stable, non-decaying anchor so the place doesn't drift away from
     * — or shrink below — its authoritative origin as visits accumulate. Null for places created
     * before anchoring existed.
     */
    val anchorLatitude: Double? = null,
    val anchorLongitude: Double? = null,
    val anchorRadiusMeters: Double? = null,
)

/**
 * A user/agent **tag**. [canonicalName] is the dedup key — [displayName] lowercased with every run
 * of whitespace/`-`/`_` collapsed to a single `-` — so "Coffee Shop", "coffee-shop", "COFFEE_SHOP"
 * collapse to one row while "coffeeshop" stays distinct. [displayName] keeps the most recent human
 * spelling. Tags attach to targets via [EntityTagEntity].
 */
@Serializable
@Entity(
    tableName = "tags",
    indices = [Index(value = ["canonicalName"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canonicalName: String,
    val displayName: String,
    val createdAtMs: Long,
    /** Package that created the tag via the data API; null = Pathline itself (the user in-app). */
    val createdBy: String? = null,
)

/**
 * Polymorphic tag<->target join. No foreign key: targets span three tables and visits/trips are
 * rebuilt with fresh ids, so integrity is maintained in code on delete/merge — the same "detach,
 * don't dangle" approach used for trip->visit references.
 */
@Serializable
@Entity(
    tableName = "entity_tags",
    primaryKeys = ["tagId", "targetType", "targetId"],
    indices = [Index("tagId"), Index(value = ["targetType", "targetId"])],
)
data class EntityTagEntity(
    val tagId: Long,
    val targetType: AnnotationTarget,
    val targetId: Long,
    val createdAtMs: Long,
    /** Package that attached the tag via the data API; null = Pathline itself (the user in-app). */
    val createdBy: String? = null,
)

/**
 * A **note** or **memory** attached to one target. [kind] discriminates: a NOTE's [content] is free
 * text; a MEMORY's [content] is a JSON object of flat string->string pairs (see the data-API design
 * doc). At most one note and one memory per target (the unique index). Polymorphic, no FK — see
 * [EntityTagEntity].
 */
@Serializable
@Entity(
    tableName = "annotations",
    indices = [Index(value = ["targetType", "targetId", "kind"], unique = true)],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetType: AnnotationTarget,
    val targetId: Long,
    val kind: AnnotationKind,
    val content: String,
    val updatedAtMs: Long,
    /** Package that last wrote this row via the data API; null = Pathline itself (the user in-app
     *  editor, or a maintenance fold). For MEMORY rows this is the last map writer; each entry's
     *  own writer lives inside [content]. */
    val updatedBy: String? = null,
)

/**
 * A **concept** — a first-class semantic group ("Japan trip 2026", "my gyms") that places, visits
 * and trips join via [ConceptMemberEntity]. Unlike a tag it has an explicit lifecycle (created,
 * renamed, deleted; identity is [id], not the name) and carries data of its own: the intrinsic
 * [kind]/[description] here, plus — being an [AnnotationTarget] — its own note, memory map and
 * tags. [canonicalName] dedups names under the same folding as tags; [kind] is a free-form but
 * canonicalized discriminator ("trip", "project") for exact filtering. [updatedAtMs]/[updatedBy]
 * track **intrinsic** edits only (name/kind/description) — membership and annotation changes carry
 * their own attribution, and so does archiving ([archivedAtMs]/[archivedBy]).
 *
 * **Archive** is a soft visibility flag, not a soft delete: an archived concept keeps its members
 * and annotations and stays readable by id, but listings/search/reverse-membership reads exclude it
 * by default (the data API's `archived` query param overrides). [archivedAtMs] null = active.
 */
@Serializable
@Entity(
    tableName = "concepts",
    indices = [Index(value = ["canonicalName"], unique = true), Index("kind")],
)
data class ConceptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canonicalName: String,
    val displayName: String,
    val kind: String? = null,
    val description: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Package that created the concept via the data API; null = Pathline itself. */
    val createdBy: String? = null,
    /** Package that last edited name/kind/description via the data API; null = Pathline itself. */
    val updatedBy: String? = null,
    /** When the concept was archived, or null = active. Defaults keep pre-archive backups decoding. */
    val archivedAtMs: Long? = null,
    /** Package that archived it via the data API; null = Pathline itself. Cleared on unarchive. */
    val archivedBy: String? = null,
)

/**
 * Polymorphic concept<->member join — same shape and integrity rules as [EntityTagEntity] (no FK;
 * maintained in code on delete/merge). [targetType] may be any [AnnotationTarget], including
 * CONCEPT — concepts can nest one level at a time (membership is stored and listed, never
 * auto-expanded; `ConceptStore.addMember` rejects cycles at write time).
 */
@Serializable
@Entity(
    tableName = "concept_members",
    primaryKeys = ["conceptId", "targetType", "targetId"],
    indices = [Index("conceptId"), Index(value = ["targetType", "targetId"])],
)
data class ConceptMemberEntity(
    val conceptId: Long,
    val targetType: AnnotationTarget,
    val targetId: Long,
    val createdAtMs: Long,
    /** Package that attached the member via the data API; null = Pathline itself. */
    val createdBy: String? = null,
)

/**
 * A stay at one location. [placeId] links to a confirmed/local place; when no local place matched,
 * the temporary Google Places candidate is stored inline in the `candidate*` fields and the visit
 * stays unconfirmed until the user accepts it (which then promotes the candidate into [places]).
 */
@Serializable
@Entity(
    tableName = "visits",
    indices = [Index("startMs"), Index("dayEpoch"), Index("placeId")],
)
data class VisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeId: Long?,
    val candidateName: String?,
    val candidateGooglePlaceId: String?,
    val candidateLatitude: Double?,
    val candidateLongitude: Double?,
    val startMs: Long,
    val endMs: Long,
    val dayEpoch: Long,
    val centroidLatitude: Double,
    val centroidLongitude: Double,
    val radiusMeters: Double,
    /** Number of usable fixes the visit's geometry was computed from (informational / debug). */
    val sampleCount: Int = 0,
    /**
     * Normalized [0,1] trustworthiness of this visit's **geometry** (center/radius), from sample
     * count, GPS accuracy, spatial dispersion and duration — see [net.extrawdw.apps.locationhistory.domain.VisitGeometry.reliabilityOf].
     * This is what weights the visit when a place's center/radius is recomputed. Distinct from
     * [confidence], which is how sure we are *which place* the visit belongs to.
     */
    val reliability: Float = 0f,
    val confirmed: Boolean,
    /** Place-attribution confidence (match distance / confirmed status), NOT geometric quality. */
    val confidence: Float,
    val isOngoing: Boolean,
)

/**
 * A single-transport-mode movement. A multi-modal journey (walk -> bus -> walk) is represented as
 * consecutive trips, each with its own [mode] and polyline — there is no separate segment table.
 * [fromVisitId]/[toVisitId] reference the visits bounding the journey this movement belongs to
 * (so a door-to-door journey can be recomputed by grouping consecutive trips that share them);
 * they are null for editor-created movements.
 */
@Serializable
@Entity(
    tableName = "trips",
    indices = [Index("startMs"), Index("dayEpoch")],
)
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromVisitId: Long?,
    val toVisitId: Long?,
    val startMs: Long,
    val endMs: Long,
    val dayEpoch: Long,
    val mode: TransportMode,
    val modeConfidence: Float,
    val encodedPolyline: String,
    val distanceMeters: Double,
    val confirmed: Boolean,
)

/** Mirror of a registered geofence so geofences can be re-armed after a reboot. */
@Serializable
@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey val requestId: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val createdAtMs: Long,
)

/**
 * One row per (data stream, week) partition that has changed since the last backup. Populated
 * entirely by SQLite triggers on the append-mostly tables (see `AppDatabase.DIRTY_TRIGGERS`),
 * so the recording/maintenance code never has to remember to mark anything — the storage layer
 * tracks it. The backup engine reads this set, re-emits exactly those partitions, then clears them.
 *
 * [weekStart] is the Monday-aligned `dayEpoch` of the week (see [net.extrawdw.apps.locationhistory.core.TimeBuckets.weekStartDayEpoch]).
 * [stream] is one of `samples`, `visits`, `trips`.
 */
@Entity(tableName = "backup_dirty_partitions", primaryKeys = ["stream", "weekStart"])
data class BackupDirtyPartitionEntity(
    val stream: String,
    val weekStart: Long,
)
