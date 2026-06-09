package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One row per successful read of Pathline data by another app through [net.extrawdw.apps.locationhistory.api.PathlineProvider].
 * This is the audit log that powers the in-app "API access" manager and the periodic warnings.
 *
 * It holds only *metadata* — which app read which collection, over what window, and when — never any
 * coordinates. It deliberately lives in its OWN unencrypted database (see [ApiAccessDatabase]) rather
 * than the encrypted [AppDatabase], so logging an access never has to touch the frozen v1 schema or
 * the backup engine, and the audit trail is not shipped off-device in backups.
 */
@Entity(
    tableName = "api_access_events",
    indices = [Index("timestampMs"), Index("packageName")],
)
data class ApiAccessEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Calling app's package name (from `ContentProvider.getCallingPackage()`), or "unknown". */
    val packageName: String,
    /** Which collection was read: "visits", "trips", or "samples". */
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
     * caller held neither READ_TIMELINE_ROUTE nor READ_LOCATION_HISTORY. Null for non-trip reads,
     * where the route is not applicable. Surfaced in the access manager so the audit trail is honest.
     */
    val routeWithheld: Boolean? = null,
    /**
     * Non-null when this was a **valid** request (known collection, required `start` present) that was
     * rejected because the caller lacks this permission — it read nothing (`rowCount` is 0). Null for a
     * successful read. Denied events are kept for the audit trail but never trigger a user alert.
     */
    val deniedPermission: String? = null,
)

/** Last-access summary for one app, projected over the log. */
data class AppLastAccess(val packageName: String, val lastMs: Long, val reads: Int)

@Dao
interface ApiAccessDao {

    @Insert
    suspend fun insert(event: ApiAccessEventEntity)

    /** Live feed of events newer than [sinceMs] (the in-app history shows only a recent window). */
    @Query("SELECT * FROM api_access_events WHERE timestampMs >= :sinceMs ORDER BY timestampMs DESC LIMIT :limit")
    fun observeSince(sinceMs: Long, limit: Int): Flow<List<ApiAccessEventEntity>>

    @Query("SELECT * FROM api_access_events WHERE timestampMs >= :sinceMs ORDER BY timestampMs DESC")
    suspend fun since(sinceMs: Long): List<ApiAccessEventEntity>

    /** One app's recent reads, for summarizing what it just accessed in a notification. */
    @Query("SELECT * FROM api_access_events WHERE packageName = :packageName AND timestampMs >= :sinceMs ORDER BY timestampMs DESC")
    suspend fun sinceForPackage(packageName: String, sinceMs: Long): List<ApiAccessEventEntity>

    /** The entire log, for the "export all" share action. */
    @Query("SELECT * FROM api_access_events ORDER BY timestampMs DESC")
    suspend fun all(): List<ApiAccessEventEntity>

    @Query("SELECT DISTINCT packageName FROM api_access_events")
    suspend fun distinctPackages(): List<String>

    @Query("SELECT COUNT(*) FROM api_access_events WHERE timestampMs >= :sinceMs")
    suspend fun countSince(sinceMs: Long): Int

    /** Last read time + read count per app, most-recent first. */
    @Query(
        "SELECT packageName AS packageName, MAX(timestampMs) AS lastMs, COUNT(*) AS reads " +
                "FROM api_access_events GROUP BY packageName ORDER BY lastMs DESC"
    )
    fun observeAppLastAccess(): Flow<List<AppLastAccess>>

    /** Deletes rows older than [cutoffMs]; returns the number removed. */
    @Query("DELETE FROM api_access_events WHERE timestampMs < :cutoffMs")
    suspend fun pruneBefore(cutoffMs: Long): Int
}

/**
 * Standalone, unencrypted database for the API access audit log. Separate from [AppDatabase] on
 * purpose — see [ApiAccessEventEntity]. Schema export is off because there is nothing sensitive to
 * migrate; if the shape ever changes, a destructive rebuild of just this log is acceptable.
 */
@Database(entities = [ApiAccessEventEntity::class], version = 1, exportSchema = false)
abstract class ApiAccessDatabase : RoomDatabase() {
    abstract fun apiAccessDao(): ApiAccessDao

    companion object {
        const val NAME = "pathline_api_access.db"
    }
}
