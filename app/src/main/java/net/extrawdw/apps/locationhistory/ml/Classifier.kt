package net.extrawdw.apps.locationhistory.ml

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.StateClassification
import net.extrawdw.apps.locationhistory.core.TransportClassification
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * The unified classification entry point used across the app. It prefers the on-device LiteRT
 * model and falls back to the [HeuristicClassifier] whenever the model is unavailable or returns
 * a low-confidence prediction. This keeps the app fully functional before any model is trained
 * while letting the personalized model take over as it learns from user confirmations.
 */
@Singleton
class Classifier @Inject constructor(
    private val models: LiteRtModelStore,
    private val heuristic: HeuristicClassifier,
) {

    fun classifyState(input: StateFeatureInput): StateClassification {
        val model = models.stateModel()
        if (model != null) {
            val probs = model.infer(Features.stateFeatures(input))
            val result = probs?.let { argmax(it) }
            if (result != null && result.second >= Constants.CONFIRM_CONFIDENCE_THRESHOLD) {
                val state = DevicePhysicalState.fromModelIndex(result.first)
                if (state == DevicePhysicalState.STATIONARY) {
                    // A false "stationary" spawns a spurious visit, so re-validate it against cheap
                    // signals: discount by GPS trust and reject if there's any real residual speed,
                    // deferring to the heuristic when the discounted confidence no longer clears the bar.
                    val adjusted = result.second * accuracyTrust(input.horizontalAccuracyMeters)
                    if (input.speedMaxMps >= 1.0f || adjusted < Constants.CONFIRM_CONFIDENCE_THRESHOLD) {
                        return heuristic.classifyState(input)
                    }
                    return StateClassification(state, adjusted)
                }
                return StateClassification(state, result.second)
            }
        }
        return heuristic.classifyState(input)
    }

    fun classifyTransport(samples: List<LocationSampleEntity>): TransportClassification {
        val model = models.transportModel()
        if (model != null && samples.size >= 2) {
            val probs = model.infer(Features.transportFeatures(samples))
            val result = probs?.let { argmax(it) }
            if (result != null && result.second >= Constants.CONFIRM_CONFIDENCE_THRESHOLD) {
                return TransportClassification(TransportMode.fromModelIndex(result.first), result.second)
            }
        }
        return heuristic.classifyTransport(samples)
    }

    private fun argmax(probs: FloatArray): Pair<Int, Float> {
        var bestIdx = 0
        var bestVal = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > bestVal) { bestVal = probs[i]; bestIdx = i }
        }
        return bestIdx to bestVal
    }

    private fun accuracyTrust(accuracyMeters: Float?): Float {
        val acc = accuracyMeters ?: return 0.75f
        return exp(-((acc.coerceAtLeast(5f) - 5f) / 35f).toDouble())
            .coerceIn(0.15, 1.0)
            .toFloat()
    }
}
