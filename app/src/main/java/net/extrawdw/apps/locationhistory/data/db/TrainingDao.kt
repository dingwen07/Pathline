package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TrainingDao {

    @Insert
    suspend fun insertStateExample(example: StateTrainingExampleEntity): Long

    @Insert
    suspend fun insertTransportExample(example: TransportTrainingExampleEntity): Long

    @Query("SELECT * FROM state_training_examples ORDER BY createdAtMs ASC")
    suspend fun allStateExamples(): List<StateTrainingExampleEntity>

    @Query("SELECT * FROM transport_training_examples ORDER BY createdAtMs ASC")
    suspend fun allTransportExamples(): List<TransportTrainingExampleEntity>

    @Query("SELECT COUNT(*) FROM state_training_examples WHERE consumed = 0")
    suspend fun unconsumedStateCount(): Int

    @Query("SELECT COUNT(*) FROM transport_training_examples WHERE consumed = 0")
    suspend fun unconsumedTransportCount(): Int

    @Query("UPDATE state_training_examples SET consumed = 1 WHERE consumed = 0")
    suspend fun markStateConsumed()

    @Query("UPDATE transport_training_examples SET consumed = 1 WHERE consumed = 0")
    suspend fun markTransportConsumed()

    /** Delete examples whose feature layout differs from the current one (see featureSchemaVersion). */
    @Query("DELETE FROM state_training_examples WHERE featureSchemaVersion != :version")
    suspend fun deleteStateExamplesNotVersion(version: Int): Int

    @Query("DELETE FROM transport_training_examples WHERE featureSchemaVersion != :version")
    suspend fun deleteTransportExamplesNotVersion(version: Int): Int
}

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
