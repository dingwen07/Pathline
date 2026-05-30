package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {

    @Insert
    suspend fun insert(place: PlaceEntity): Long

    @Update
    suspend fun update(place: PlaceEntity)

    @Query("SELECT * FROM places ORDER BY visitCount DESC, name ASC")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun byId(id: Long): PlaceEntity?

    @Query("SELECT * FROM places WHERE id = :id")
    fun observeById(id: Long): Flow<PlaceEntity?>

    /**
     * Candidate local places near a point: pre-filtered with an indexed bounding box, then
     * distance-checked in [net.extrawdw.apps.locationhistory.domain.PlaceMatcher].
     */
    @Query(
        "SELECT * FROM places WHERE latitude BETWEEN :minLat AND :maxLat " +
            "AND longitude BETWEEN :minLon AND :maxLon"
    )
    suspend fun inBoundingBox(
        minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
    ): List<PlaceEntity>

    @Query("UPDATE places SET visitCount = visitCount + 1 WHERE id = :id")
    suspend fun incrementVisitCount(id: Long)

    @Query("UPDATE places SET latitude = :lat, longitude = :lon, radiusMeters = :radius WHERE id = :id")
    suspend fun updateCenterRadius(id: Long, lat: Double, lon: Double, radius: Double)

    @Query("SELECT COUNT(*) FROM places")
    suspend fun count(): Int
}
