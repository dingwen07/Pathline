package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.core.NetworkTransport
import net.extrawdw.apps.locationhistory.core.StateClassification
import net.extrawdw.apps.locationhistory.core.TransportClassification
import net.extrawdw.apps.locationhistory.core.TransportMode
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Input to the device-state estimate for a single moment, assembled by the recorder from the
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
    /** Whether recent fixes show real net translation — corroborates (or overrides) an AR STILL. */
    val translating: Boolean = false,
)

/**
 * Transparent, rule-of-thumb classifier — the app's only on-device classifier.
 *
 * [classifyState] is the recorder's deterministic movement estimate: it tunes sampling cadence and
 * is stored on each sample as *evidence* of what the recorder believed, never as timeline truth
 * (the timeline rebuild re-derives stays/movement from the sample sequence). [classifyTransport]
 * is the bootstrap mode scorer until the offline-trained span model ships (see
 * docs/recorder-timeline-refactor-plan.md); it deliberately reports modest confidence so its
 * outputs surface as "unconfirmed" and never masquerade as ground truth.
 */
@Singleton
class HeuristicClassifier @Inject constructor() {

    fun classifyState(input: StateFeatureInput): StateClassification {
        val arStrong = (input.arConfidence ?: 0) >= 70
        val ar = input.arActivity?.uppercase()
        val accuracyTrust = accuracyTrust(input.horizontalAccuracyMeters)

        // AR is the movement authority (debounced + motion-trained), so a strong AR verdict wins BEFORE
        // GPS speed -- indoor multipath Doppler must not override a correct STILL (the 06-13 phantom
        // that stamped WALKING/CYCLING on a motionless phone). But STILL is corroborated with net
        // displacement: on a slow/smooth ride AR mislabels STILL, so when the position is actually
        // progressing ([input.translating]) we fall through to the speed branch instead of stamping
        // STATIONARY on a moving vehicle. A poor radius only lowers STILL *confidence*. Speed/IMU are
        // the fallback below, for when AR is weak/absent (permission off, cold-start silence, no-AR OEM).
        if (arStrong) {
            when (ar) {
                "STILL" -> if (!input.translating) {
                    return StateClassification(DevicePhysicalState.STATIONARY, 0.8f * accuracyTrust)
                }

                "WALKING", "ON_FOOT" -> return StateClassification(DevicePhysicalState.WALKING, 0.72f)
                "RUNNING" -> return StateClassification(DevicePhysicalState.RUNNING, 0.72f)
                "ON_BICYCLE" -> return StateClassification(DevicePhysicalState.CYCLING, 0.7f)
                "IN_VEHICLE" -> return StateClassification(DevicePhysicalState.IN_VEHICLE, 0.75f)
            }
        }

        val speed = input.speedMps ?: input.speedMeanMps
        if (speed >= 1.0f || input.speedMaxMps >= 1.4f) {
            return when {
                speed < 2.8f -> StateClassification(DevicePhysicalState.WALKING, 0.72f)
                speed < 6.0f && input.motionVariance > 3f ->
                    StateClassification(DevicePhysicalState.RUNNING, 0.62f)

                speed < 8.5f -> StateClassification(DevicePhysicalState.CYCLING, 0.56f)
                else -> StateClassification(DevicePhysicalState.IN_VEHICLE, 0.68f)
            }
        }

        // AR weak/absent (or AR-STILL-while-translating with no GPS speed): only still vs slow on-foot
        // is left at sub-walking speed -- faster modes always carry speed >= 1.0 and are handled above.
        return if (speed < 0.5f && input.motionVariance < 1.5f) {
            StateClassification(DevicePhysicalState.STATIONARY, 0.7f * accuracyTrust)
        } else {
            StateClassification(DevicePhysicalState.WALKING, 0.55f)
        }
    }

    private fun accuracyTrust(accuracyMeters: Float?): Float {
        val acc = accuracyMeters ?: return 0.75f
        return exp(-((acc.coerceAtLeast(5f) - 5f) / 35f).toDouble())
            .coerceIn(0.15, 1.0)
            .toFloat()
    }

    fun classifyTransport(samples: List<LocationSampleEntity>): TransportClassification {
        if (samples.size < 2) return TransportClassification(TransportMode.UNKNOWN, 0f)
        val speeds = samples.map { (it.speed ?: 0f).coerceAtLeast(0f) }
        val mean = speeds.average().toFloat()
        val p85 = speeds.sorted()[((speeds.size - 1) * 0.85f).toInt()]
        val accels = (1 until speeds.size).map { speeds[it] - speeds[it - 1] }
        val accelStd = if (accels.isEmpty()) 0f else
            sqrt(accels.map { it * it }.average().toFloat())
        val noCellFraction = samples.count { it.hasCellService == false }.toFloat() / samples.size
        val vehicleAr =
            samples.count { it.arActivity?.uppercase() == "IN_VEHICLE" }.toFloat() / samples.size

        return when {
            p85 < 2.5f -> TransportClassification(TransportMode.WALKING, 0.6f)
            p85 < 6.0f && accelStd > 1.0f -> TransportClassification(TransportMode.RUNNING, 0.45f)
            p85 < 8.5f && vehicleAr < 0.3f -> TransportClassification(TransportMode.CYCLING, 0.45f)
            // FLIGHT must be checked before RAIL: a flight is also fast, smooth at sparse cruise
            // sampling, and (in airplane mode) cell-free, so the rail arm would capture it.
            p85 > 80f -> TransportClassification(TransportMode.FLIGHT, 0.6f)
            // Fast, smooth, frequently losing cell service -> likely rail/subway.
            p85 > 14f && accelStd < 1.2f && noCellFraction > 0.3f ->
                TransportClassification(TransportMode.RAIL, 0.5f)

            // Road-speed average that didn't match a more specific mode above: a road vehicle,
            // defaulting to car (bus is indistinguishable from speed alone).
            mean > 2f -> TransportClassification(TransportMode.CAR, 0.45f)
            else -> TransportClassification(TransportMode.UNKNOWN, 0.2f)
        }
    }
}
