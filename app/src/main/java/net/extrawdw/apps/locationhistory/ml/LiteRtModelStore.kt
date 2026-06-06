package net.extrawdw.apps.locationhistory.ml

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.TransportMode
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the lifecycle of the two LiteRT models. Each model's base graph is bundled as an asset; if
 * the asset is absent (e.g. before `build_models.py` has been run) the store reports the model as
 * unavailable and the pipeline transparently falls back to the [HeuristicClassifier].
 *
 * Restored from / saved to a personalized checkpoint in the app's files dir so user-confirmed
 * training carries across launches.
 */
@Singleton
class LiteRtModelStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private var stateModel: TrainableTfliteModel? = null
    private var transportModel: TrainableTfliteModel? = null
    private var loaded = false

    val stateCheckpoint: File get() = File(context.filesDir, Constants.STATE_CHECKPOINT_FILE)
    val transportCheckpoint: File
        get() = File(
            context.filesDir,
            Constants.TRANSPORT_CHECKPOINT_FILE
        )

    fun stateModel(): TrainableTfliteModel? = synchronized(lock) { ensureLoaded(); stateModel }
    fun transportModel(): TrainableTfliteModel? =
        synchronized(lock) { ensureLoaded(); transportModel }

    /** Re-create interpreters from disk (called after a training run rewrites the checkpoints). */
    fun reload() = synchronized(lock) {
        stateModel?.close(); transportModel?.close()
        stateModel = null; transportModel = null; loaded = false
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        stateModel = loadModel(
            Constants.STATE_MODEL_ASSET,
            Features.STATE_FEATURE_DIM,
            DevicePhysicalState.MODEL_CLASSES.size,
            stateCheckpoint,
        )
        transportModel = loadModel(
            Constants.TRANSPORT_MODEL_ASSET,
            Features.TRANSPORT_FEATURE_DIM,
            TransportMode.MODEL_CLASSES.size,
            transportCheckpoint,
        )
    }

    private fun loadModel(
        assetPath: String, featureDim: Int, numClasses: Int, checkpoint: File,
    ): TrainableTfliteModel? = runCatching {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        TrainableTfliteModel(TrainableTfliteModel.directBuffer(bytes), featureDim, numClasses)
            .also { it.restoreIfPresent(checkpoint) }
            .also {
                AppLog.i(
                    TAG,
                    "loaded $assetPath training=${it.supportsTraining()} checkpoint=${checkpoint.exists()}",
                )
            }
    }.onFailure {
        AppLog.w(TAG, "failed to load $assetPath: ${it.message}")
    }.getOrNull()

    private companion object {
        const val TAG = "ModelStore"
    }
}
