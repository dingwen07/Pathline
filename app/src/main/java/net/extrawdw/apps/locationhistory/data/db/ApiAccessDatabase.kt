package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * One row per recorded Pathline data-API access by another app through
 * [net.extrawdw.apps.locationhistory.api.PathlineProvider]. This includes successful reads,
 * permission-denied valid requests, and annotation writes. The log powers the in-app "API access"
 * manager and periodic warnings.
 *
 * It holds only *metadata* — which app read which collection, over what window, and when — never any
 * coordinates. It deliberately lives in its OWN unencrypted database (see [ApiAccessDatabase]) rather
 * than the encrypted [AppDatabase], so logging an access never has to open the location-history
 * database or involve its backup engine, and the audit trail is not shipped off-device in backups.
 */
@Entity(
    tableName = "api_access_events",
    indices = [Index("timestampMs"), Index("packageName")],
)
data class ApiAccessEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Calling app's package name (from `ContentProvider.getCallingPackage()`), or "unknown". */
    val packageName: String,
    /** Which collection or annotation operation was accessed. */
    val dataType: String,
    /** The time window the caller requested (epoch ms). */
    val startMs: Long,
    val endMs: Long,
    /** How many rows were returned. */
    val rowCount: Int,
    /** When the read happened (epoch ms). */
    val timestampMs: Long,
    /**
     * Optional batch-correlation key supplied by the caller (see `PathlineContract.QueryParams.GROUP`),
     * validated to be within the grouping window. Rows that share it (same package) are aggregated as
     * one expandable entry in the access manager. Null when the caller didn't group, or sent a stale/
     * invalid value.
     */
    val groupId: Long? = null,
    /**
     * For a `trips` read, whether the `encoded_polyline` (route) column was withheld because the
     * caller held neither READ_TIMELINE_ROUTES nor READ_LOCATION_HISTORY. Null for non-trip reads,
     * where the route is not applicable. Surfaced in the access manager so the audit trail is honest.
     */
    val routeWithheld: Boolean? = null,
    /**
     * Non-null when this was a **valid** request (known collection, required `start` present) that was
     * rejected because the caller lacks this permission — it read nothing (`rowCount` is 0). Null for a
     * successful read. Denied events are kept for the audit trail but never trigger a user alert.
     */
    val deniedPermission: String? = null,
    /**
     * True when this event was an annotation **write** (tag/note/memory insert, update or delete
     * through the data API) rather than a read. [rowCount] then counts the rows/links/keys actually
     * changed, and [startMs]/[endMs] carry the zero-width "now" the write happened at.
     */
    val isWrite: Boolean = false,
)

/** Last-access summary for one app, projected over the log. */
data class AppLastAccess(val packageName: String, val lastMs: Long, val reads: Int)

/**
 * Records that a calling app has been *exposed to* a saved place — i.e. it read a `visits` row whose
 * [placeId] points at that place through [net.extrawdw.apps.locationhistory.api.PathlineProvider].
 *
 * This is the access-scoping ledger for the `places` collection: an app may resolve a place's details
 * (name, address) and its visit history ONLY for places it has already legitimately encountered in an
 * authorized timeline read. Reading `visits` is the sole way a grant is created; the place/history
 * endpoints only ever consult it. Like [ApiAccessEventEntity] it lives in the separate, unencrypted
 * API-access DB and holds no coordinates — just durable authorization scope mapping a package to the
 * saved-place ids it may see.
 */
@Entity(
    tableName = "api_place_grants",
    primaryKeys = ["packageName", "placeId"],
    indices = [Index("packageName")],
)
data class ApiPlaceGrantEntity(
    /** Calling app the grant belongs to (from `ContentProvider.getCallingPackage()`). */
    val packageName: String,
    /** Saved-place id the app has seen, referencing `places.id` in the main (encrypted) DB. */
    val placeId: Long,
    /** When the app first saw this place (epoch ms). Preserved across later reads. */
    val firstGrantedMs: Long,
    /** When the app most recently saw this place (epoch ms). */
    val lastGrantedMs: Long,
)

@Dao
interface ApiPlaceGrantDao {

    /** Create grants for newly-seen (package, place) pairs; existing pairs keep their first-seen time. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(grants: List<ApiPlaceGrantEntity>)

    /** Refresh the last-seen time for places this app just read again. */
    @Query("UPDATE api_place_grants SET lastGrantedMs = :nowMs WHERE packageName = :packageName AND placeId IN (:placeIds)")
    suspend fun touch(packageName: String, placeIds: List<Long>, nowMs: Long)

    /** Every place [packageName] has been exposed to (for an unfiltered `places` read). */
    @Query("SELECT placeId FROM api_place_grants WHERE packageName = :packageName")
    suspend fun grantedPlaceIds(packageName: String): List<Long>

    /** The subset of [placeIds] that [packageName] is allowed to see (for a filtered `places` read). */
    @Query("SELECT placeId FROM api_place_grants WHERE packageName = :packageName AND placeId IN (:placeIds)")
    suspend fun grantedAmong(packageName: String, placeIds: List<Long>): List<Long>

    /** Whether [packageName] may read details/history for a single [placeId]. */
    @Query("SELECT EXISTS(SELECT 1 FROM api_place_grants WHERE packageName = :packageName AND placeId = :placeId)")
    suspend fun isGranted(packageName: String, placeId: Long): Boolean
}

@Dao
interface ApiAccessDao {

    @Insert
    suspend fun insert(event: ApiAccessEventEntity)

    /** Live feed of events newer than [sinceMs] (the in-app history shows only a recent window). */
    @Query("SELECT * FROM api_access_events WHERE timestampMs >= :sinceMs ORDER BY timestampMs DESC LIMIT :limit")
    fun observeSince(sinceMs: Long, limit: Int): Flow<List<ApiAccessEventEntity>>

    @Query("SELECT * FROM api_access_events WHERE timestampMs >= :sinceMs ORDER BY timestampMs DESC")
    suspend fun since(sinceMs: Long): List<ApiAccessEventEntity>

    /** One app's recent access events, for summarizing what it just accessed in a notification. */
    @Query("SELECT * FROM api_access_events WHERE packageName = :packageName AND timestampMs >= :sinceMs ORDER BY timestampMs DESC")
    suspend fun sinceForPackage(packageName: String, sinceMs: Long): List<ApiAccessEventEntity>

    /** The entire log, for the "export all" share action. */
    @Query("SELECT * FROM api_access_events ORDER BY timestampMs DESC")
    suspend fun all(): List<ApiAccessEventEntity>

    @Query("SELECT DISTINCT packageName FROM api_access_events")
    suspend fun distinctPackages(): List<String>

    @Query("SELECT COUNT(*) FROM api_access_events WHERE timestampMs >= :sinceMs")
    suspend fun countSince(sinceMs: Long): Int

    /** Last access time + event count per app, most-recent first. */
    @Query(
        "SELECT packageName AS packageName, MAX(timestampMs) AS lastMs, COUNT(*) AS reads " +
                "FROM api_access_events GROUP BY packageName ORDER BY lastMs DESC"
    )
    fun observeAppLastAccess(): Flow<List<AppLastAccess>>

    /** Deletes rows older than [cutoffMs]; returns the number removed. */
    @Query("DELETE FROM api_access_events WHERE timestampMs < :cutoffMs")
    suspend fun pruneBefore(cutoffMs: Long): Int

    /** Deletes the whole access log (the place-grant ledger is untouched); returns the count. */
    @Query("DELETE FROM api_access_events")
    suspend fun clearAll(): Int
}

/**
 * Standalone, unencrypted database for the API access audit log and place-grant ledger. It is
 * separate from [AppDatabase] so access logging never opens the encrypted history database, but its
 * schema is exported and migrated because [ApiPlaceGrantEntity] is durable authorization state.
 */
@Database(
    entities = [ApiAccessEventEntity::class, ApiPlaceGrantEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ApiAccessDatabase : RoomDatabase() {
    abstract fun apiAccessDao(): ApiAccessDao
    abstract fun apiPlaceGrantDao(): ApiPlaceGrantDao

    companion object {
        const val NAME = "pathline_api_access.db"

        /**
         * v2: the [ApiAccessEventEntity.isWrite] column (annotation writes land in the same log).
         * The database also holds the place-grant ledger, so this migration preserves both tables.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE api_access_events ADD COLUMN isWrite INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
