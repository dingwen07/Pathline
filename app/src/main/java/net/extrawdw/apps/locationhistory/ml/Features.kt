package net.extrawdw.apps.locationhistory.ml

import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import net.extrawdw.apps.locationhistory.core.NetworkTransport
import kotlin.math.sqrt

/**
 * Input to the device-state classifier for a single moment, assembled by the recorder from the
 * current fix, a short window of recent speeds, motion-sensor energy and network state.
 */
data class StateFeatureInput(
    val speedMps: Float?,
    val speedMeanMps: Float,
    val speedMaxMps: Float,
    val speedVariance: Float,
    val motionVariance: Float,
    val horizontalAccuracyMeters: Float?,
    val arActivity: String?,
    val arConfidence: Int?,
    val networkTransport: NetworkTransport?,
    val hasCellService: Boolean?,
    val cellSignalDbm: Int?,
)

/**
 * Turns raw signals into the fixed-length, normalized feature vectors consumed by both the
 * heuristic fallback and the LiteRT models. The dimensions and channel order here are the
 * contract with `ml/training/build_models.py`; changing them requires rebuilding the models.
 */
object Features {

    const val STATE_FEATURE_DIM = 13
    const val TRANSPORT_FEATURE_DIM = 16

    /**
     * Layout version of each feature vector, stamped onto every training example
     * ([net.extrawdw.apps.locationhistory.data.db.StateTrainingExampleEntity.featureSchemaVersion]).
     * **Bump the relevant one whenever you change the order, meaning, normalization, or dimension of
     * that feature set, OR the order/length of the model's class list**
     * (`DevicePhysicalState.MODEL_CLASSES` / `TransportMode.MODEL_CLASSES`) — each example's stored
     * `label` is an index into that list, so reordering it silently remaps old labels. Examples from an
     * older version are then purged instead of mis-trained, since the stored CSV/label carry no names.
     */
    const val STATE_FEATURE_SCHEMA_VERSION = 1
    const val TRANSPORT_FEATURE_SCHEMA_VERSION = 1

    private const val MAX_SPEED_MPS = 40f          // ~144 km/h, normalization ceiling
    private const val MAX_MOTION_VAR = 20f
    private const val SLOW_SPEED_MPS = 0.5f         // below this a sample counts as "stopped"

    fun stateFeatures(input: StateFeatureInput): FloatArray {
        val ar = arOneHot(input.arActivity, input.arConfidence)
        return floatArrayOf(
            norm(input.speedMps ?: 0f, MAX_SPEED_MPS),
            norm(input.speedMeanMps, MAX_SPEED_MPS),
            norm(input.speedMaxMps, MAX_SPEED_MPS),
            norm(sqrt(input.speedVariance), MAX_SPEED_MPS),
            norm(input.motionVariance, MAX_MOTION_VAR),
            ar[0], ar[1], ar[2], ar[3], ar[4],
            if (input.networkTransport == NetworkTransport.WIFI) 1f else 0f,
            if (input.hasCellService == true) 1f else 0f,
            cellNorm(input.cellSignalDbm),
        )
    }

    /** Device-state feature vector for a confirmed stationary stay (used as a training example). */
    fun stationaryFeatures(samples: List<LocationSampleEntity>): FloatArray {
        if (samples.isEmpty()) return stateFeatures(
            StateFeatureInput(0f, 0f, 0f, 0f, 0f, null, "STILL", 100, null, null, null),
        )
        val speeds = samples.map { (it.speed ?: 0f).coerceAtLeast(0f) }
        val mean = speeds.average().toFloat()
        val last = samples.last()
        return stateFeatures(
            StateFeatureInput(
                speedMps = speeds.last(),
                speedMeanMps = mean,
                speedMaxMps = speeds.max(),
                speedVariance = speeds.map { (it - mean) * (it - mean) }.average().toFloat(),
                motionVariance = 0f,
                horizontalAccuracyMeters = last.accuracy,
                arActivity = samples.mapNotNull { it.arActivity }.lastOrNull(),
                arConfidence = last.arConfidence,
                networkTransport = last.networkTransport,
                hasCellService = last.hasCellService,
                cellSignalDbm = last.cellSignalDbm,
            ),
        )
    }

    fun transportFeatures(samples: List<LocationSampleEntity>): FloatArray {
        if (samples.isEmpty()) return FloatArray(TRANSPORT_FEATURE_DIM)
        val speeds = samples.map { (it.speed ?: 0f).coerceAtLeast(0f) }
        val mean = speeds.average().toFloat()
        val max = speeds.max()
        val p85 = percentile(speeds, 0.85f)
        val std = sqrt(speeds.map { (it - mean) * (it - mean) }.average().toFloat())
        val stopFraction = speeds.count { it < SLOW_SPEED_MPS }.toFloat() / speeds.size
        val accelVar = accelerationVariance(speeds)

        var still = 0; var walk = 0; var run = 0; var bike = 0; var vehicle = 0
        var wifi = 0; var noCell = 0
        var signalSum = 0f; var signalN = 0
        for (s in samples) {
            when (arBucket(s.arActivity)) {
                0 -> still++; 1 -> walk++; 2 -> run++; 3 -> bike++; 4 -> vehicle++
            }
            if (s.networkTransport == NetworkTransport.WIFI) wifi++
            if (s.hasCellService == false) noCell++
            s.cellSignalDbm?.let { signalSum += cellNorm(it); signalN++ }
        }
        val n = samples.size.toFloat()
        val totalDist = pathDistance(samples)
        val durationSec = ((samples.last().timestampMs - samples.first().timestampMs) / 1000).toFloat()

        return floatArrayOf(
            norm(mean, MAX_SPEED_MPS),
            norm(max, MAX_SPEED_MPS),
            norm(p85, MAX_SPEED_MPS),
            norm(std, MAX_SPEED_MPS),
            stopFraction,
            norm(accelVar, MAX_MOTION_VAR),
            still / n, walk / n, run / n, bike / n, vehicle / n,
            wifi / n,
            noCell / n,
            if (signalN > 0) signalSum / signalN else 0f,
            (totalDist / 20_000.0).coerceIn(0.0, 1.0).toFloat(),
            (durationSec / 3_600f).coerceIn(0f, 1f),
        )
    }

    /** One-hot-ish encoding of an Activity Recognition label weighted by its confidence (0..1). */
    private fun arOneHot(activity: String?, confidence: Int?): FloatArray {
        val out = FloatArray(5)
        val idx = arBucket(activity)
        if (idx in 0..4) out[idx] = (confidence ?: 100).coerceIn(0, 100) / 100f
        return out
    }

    /** Maps an AR label to a state bucket: 0 still, 1 walking, 2 running, 3 cycling, 4 vehicle, -1 unknown. */
    private fun arBucket(activity: String?): Int = when (activity?.uppercase()) {
        "STILL" -> 0
        "WALKING", "ON_FOOT" -> 1
        "RUNNING" -> 2
        "ON_BICYCLE" -> 3
        "IN_VEHICLE" -> 4
        else -> -1
    }

    private fun accelerationVariance(speeds: List<Float>): Float {
        if (speeds.size < 3) return 0f
        val accels = (1 until speeds.size).map { speeds[it] - speeds[it - 1] }
        val mean = accels.average().toFloat()
        return accels.map { (it - mean) * (it - mean) }.average().toFloat()
    }

    private fun pathDistance(samples: List<LocationSampleEntity>): Double {
        var total = 0.0
        for (i in 1 until samples.size) {
            total += net.extrawdw.apps.locationhistory.core.Geo.distanceMeters(
                samples[i - 1].latitude, samples[i - 1].longitude,
                samples[i].latitude, samples[i].longitude,
            )
        }
        return total
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun norm(value: Float, max: Float): Float = (value / max).coerceIn(0f, 1f)

    /** Maps cellular dBm (~ -120..-50) into 0..1. */
    private fun cellNorm(dbm: Int?): Float =
        if (dbm == null) 0f else ((dbm + 120f) / 70f).coerceIn(0f, 1f)
}
