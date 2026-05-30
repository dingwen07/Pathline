package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
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
    val category: String?,
    val source: PlaceSource,
    val googlePlaceId: String?,
    val address: String?,
    val confirmed: Boolean,
    val createdAtMs: Long,
    val visitCount: Int = 0,
    /** When true the user has pinned the center/radius — the app must not auto-update them. */
    val fixed: Boolean = false,
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
    val confirmed: Boolean,
    val confidence: Float,
    val isOngoing: Boolean,
)

/**
 * A single-transport-mode movement. A multi-modal journey (walk → bus → walk) is represented as
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
 * A training example for the device-state model. The feature vector is stored as a comma-separated
 * float string; [fromUserConfirmation] examples are ground truth and weighted accordingly.
 */
@Serializable
@Entity(tableName = "state_training_examples")
data class StateTrainingExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val features: String,
    val label: Int,
    val fromUserConfirmation: Boolean,
    val createdAtMs: Long,
    val consumed: Boolean = false,
)

/** A training example for the transport-mode model. */
@Serializable
@Entity(tableName = "transport_training_examples")
data class TransportTrainingExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val features: String,
    val label: Int,
    val fromUserConfirmation: Boolean,
    val createdAtMs: Long,
    val consumed: Boolean = false,
)

/**
 * One row per (data stream, week) partition that has changed since the last backup. Populated
 * entirely by SQLite triggers on the four append-mostly tables (see `AppDatabase.DIRTY_TRIGGERS`),
 * so the recording/maintenance code never has to remember to mark anything — the storage layer
 * tracks it. The backup engine reads this set, re-emits exactly those partitions, then clears them.
 *
 * [weekStart] is the Monday-aligned `dayEpoch` of the week (see [net.extrawdw.apps.locationhistory.core.TimeBuckets.weekStartDayEpoch]).
 * [stream] is one of `samples`, `visits`, `trips`, `segments`.
 */
@Entity(tableName = "backup_dirty_partitions", primaryKeys = ["stream", "weekStart"])
data class BackupDirtyPartitionEntity(
    val stream: String,
    val weekStart: Long,
)
