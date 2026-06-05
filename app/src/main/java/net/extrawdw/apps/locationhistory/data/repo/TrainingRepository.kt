package net.extrawdw.apps.locationhistory.data.repo

import net.extrawdw.apps.locationhistory.data.db.StateTrainingExampleEntity
import net.extrawdw.apps.locationhistory.data.db.TrainingDao
import net.extrawdw.apps.locationhistory.data.db.TransportTrainingExampleEntity
import net.extrawdw.apps.locationhistory.ml.Features
import javax.inject.Inject
import javax.inject.Singleton

/** Stores and reads the user-confirmed training examples that drive on-device personalization. */
@Singleton
class TrainingRepository @Inject constructor(
    private val dao: TrainingDao,
) {
    suspend fun addStateExample(features: FloatArray, label: Int, fromUserConfirmation: Boolean) {
        dao.insertStateExample(
            StateTrainingExampleEntity(
                features = encode(features),
                label = label,
                fromUserConfirmation = fromUserConfirmation,
                createdAtMs = System.currentTimeMillis(),
                featureSchemaVersion = Features.STATE_FEATURE_SCHEMA_VERSION,
            ),
        )
    }

    suspend fun addTransportExample(features: FloatArray, label: Int, fromUserConfirmation: Boolean) {
        dao.insertTransportExample(
            TransportTrainingExampleEntity(
                features = encode(features),
                label = label,
                fromUserConfirmation = fromUserConfirmation,
                createdAtMs = System.currentTimeMillis(),
                featureSchemaVersion = Features.TRANSPORT_FEATURE_SCHEMA_VERSION,
            ),
        )
    }

    suspend fun allStateExamples() = dao.allStateExamples()
    suspend fun allTransportExamples() = dao.allTransportExamples()
    suspend fun unconsumedStateCount() = dao.unconsumedStateCount()
    suspend fun unconsumedTransportCount() = dao.unconsumedTransportCount()
    suspend fun markStateConsumed() = dao.markStateConsumed()
    suspend fun markTransportConsumed() = dao.markTransportConsumed()

    /** Drop examples whose feature layout no longer matches the current code, so a changed
     *  [Features] vector can't silently corrupt training (and stale rows don't inflate the
     *  unconsumed count forever). Returns the number purged. */
    suspend fun purgeStaleStateExamples() =
        dao.deleteStateExamplesNotVersion(Features.STATE_FEATURE_SCHEMA_VERSION)
    suspend fun purgeStaleTransportExamples() =
        dao.deleteTransportExamplesNotVersion(Features.TRANSPORT_FEATURE_SCHEMA_VERSION)

    companion object {
        fun encode(features: FloatArray): String = features.joinToString(",")
        fun decode(features: String): FloatArray =
            if (features.isEmpty()) FloatArray(0)
            else features.split(",").map { it.toFloat() }.toFloatArray()
    }
}
