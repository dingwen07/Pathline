package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

@Dao
interface PlaceCoordinateRepairDao {
    @Insert
    suspend fun insert(repair: PlaceCoordinateRepairEntity): Long

    @Query(
        "SELECT * FROM place_coordinate_repairs " +
                "WHERE placeId = :placeId AND undoneAtMs IS NULL ORDER BY id DESC LIMIT 1"
    )
    suspend fun latestActive(placeId: Long): PlaceCoordinateRepairEntity?

    @Query("UPDATE place_coordinate_repairs SET undoneAtMs = :undoneAtMs WHERE id = :id")
    suspend fun markUndone(id: Long, undoneAtMs: Long)
}
