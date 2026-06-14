package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins [HeuristicClassifier.classifyState]'s AR-vs-GPS ordering (review #3): a strong AR verdict wins
 * before GPS speed so indoor multipath can't override a correct STILL, BUT a STILL is corroborated
 * with net displacement so a slow/smooth ride AR mislabels STILL is not stamped STATIONARY.
 */
class HeuristicClassifierTest {

    private val c = HeuristicClassifier()

    private fun input(
        speedMps: Float? = null,
        speedMax: Float = 0f,
        motionVariance: Float = 0f,
        accuracy: Float? = 10f,
        ar: String? = null,
        arConf: Int? = null,
        translating: Boolean = false,
    ) = StateFeatureInput(
        speedMps = speedMps,
        speedMeanMps = speedMps ?: 0f,
        speedMaxMps = speedMax,
        speedVariance = 0f,
        motionVariance = motionVariance,
        horizontalAccuracyMeters = accuracy,
        arActivity = ar,
        arConfidence = arConf,
        networkTransport = null,
        hasCellService = null,
        cellSignalDbm = null,
        translating = translating,
    )

    @Test
    fun arStillNotTranslating_isStationary() {
        assertEquals(
            DevicePhysicalState.STATIONARY,
            c.classifyState(input(ar = "STILL", arConf = 90, translating = false)).state,
        )
    }

    @Test
    fun phantomSpeedUnderArStill_staysStationary() {
        // The 06-13 phantom: GPS reports 8 m/s on a motionless phone (AR=STILL, no net translation).
        // AR wins before the speed branch, so this stays STATIONARY instead of being stamped IN_VEHICLE.
        assertEquals(
            DevicePhysicalState.STATIONARY,
            c.classifyState(input(speedMps = 8f, speedMax = 8f, ar = "STILL", arConf = 90, translating = false)).state,
        )
    }

    @Test
    fun arStillWhileTranslating_classifiesMotion() {
        // A real slow/smooth ride AR mislabels STILL: net displacement corroborates motion, so STILL is
        // NOT honored — it falls through to the speed branch and classifies the movement.
        val state = c.classifyState(input(speedMps = 2f, ar = "STILL", arConf = 90, translating = true)).state
        assertNotEquals(DevicePhysicalState.STATIONARY, state)
        assertEquals(DevicePhysicalState.WALKING, state)
    }

    @Test
    fun arVehicle_isInVehicle() {
        assertEquals(
            DevicePhysicalState.IN_VEHICLE,
            c.classifyState(input(ar = "IN_VEHICLE", arConf = 90)).state,
        )
    }

    @Test
    fun arWeak_fallsBackToSpeed() {
        // No usable AR -> GPS speed decides.
        assertEquals(
            DevicePhysicalState.WALKING,
            c.classifyState(input(speedMps = 2f, ar = null, arConf = null)).state,
        )
        assertEquals(
            DevicePhysicalState.STATIONARY,
            c.classifyState(input(speedMps = 0f, ar = null, arConf = null)).state,
        )
    }
}
