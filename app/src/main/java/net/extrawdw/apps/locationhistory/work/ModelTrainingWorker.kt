package net.extrawdw.apps.locationhistory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import net.extrawdw.apps.locationhistory.data.repo.TrainingRepository
import net.extrawdw.apps.locationhistory.ml.LiteRtModelStore
import net.extrawdw.apps.locationhistory.ml.TrainableTfliteModel

/**
 * Retrains the on-device LiteRT models from accumulated (user-confirmed) examples. It is only ever
 * enqueued with charging / battery-not-low constraints (see [WorkScheduler]) so the
 * model improves without ever costing the user battery during the day. A no-op when the base model
 * assets are absent — the app simply keeps using the heuristic classifier.
 */
@HiltWorker
class ModelTrainingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val models: LiteRtModelStore,
    private val trainingRepository: TrainingRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        net.extrawdw.apps.locationhistory.core.AppLog.i("TrainingWorker", "training run starting (charging)")
        var trainedAnything = false

        // Drop examples whose feature layout no longer matches the current code (e.g. restored from an
        // older backup, or produced before a Features change) so they can't corrupt the retrain.
        val purged = trainingRepository.purgeStaleStateExamples() + trainingRepository.purgeStaleTransportExamples()
        if (purged > 0) net.extrawdw.apps.locationhistory.core.AppLog.i("TrainingWorker", "purged $purged stale-layout examples")

        models.stateModel()?.takeIf { it.supportsTraining() }?.let { model ->
            val examples = trainingRepository.allStateExamples()
                .map { TrainingRepository.decode(it.features) to it.label }
                .filter { it.first.size == model.featureDim && it.second in 0 until model.numClasses }
            net.extrawdw.apps.locationhistory.core.AppLog.i(
                "TrainingWorker",
                "state examples=${examples.size}",
            )
            if (examples.isNotEmpty()) {
                trainBatch(model, examples)
                model.save(models.stateCheckpoint)
                trainingRepository.markStateConsumed()
                trainedAnything = true
            }
        }

        models.transportModel()?.takeIf { it.supportsTraining() }?.let { model ->
            val examples = trainingRepository.allTransportExamples()
                .map { TrainingRepository.decode(it.features) to it.label }
                .filter { it.first.size == model.featureDim && it.second in 0 until model.numClasses }
            net.extrawdw.apps.locationhistory.core.AppLog.i(
                "TrainingWorker",
                "transport examples=${examples.size}",
            )
            if (examples.isNotEmpty()) {
                trainBatch(model, examples)
                model.save(models.transportCheckpoint)
                trainingRepository.markTransportConsumed()
                trainedAnything = true
            }
        }

        if (trainedAnything) models.reload()
        return Result.success()
    }

    private fun trainBatch(model: TrainableTfliteModel, examples: List<Pair<FloatArray, Int>>) {
        val x = Array(examples.size) { examples[it].first }
        val y = Array(examples.size) { i ->
            FloatArray(model.numClasses).also { row ->
                examples[i].second.takeIf { it in 0 until model.numClasses }?.let { row[it] = 1f }
            }
        }
        model.train(x, y, epochs = EPOCHS)
    }

    private companion object {
        const val EPOCHS = 30
    }
}
