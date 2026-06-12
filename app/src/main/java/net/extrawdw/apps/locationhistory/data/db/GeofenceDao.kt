package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface GeofenceDao {

    @Insert
    suspend fun insert(geofence: GeofenceEntity)

    @Query("DELETE FROM geofences WHERE requestId = :requestId")
    suspend fun delete(requestId: String)

    @Query("DELETE FROM geofences")
    suspend fun clear()

    @Query("SELECT * FROM geofences")
    suspend fun all(): List<GeofenceEntity>
}
