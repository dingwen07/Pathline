package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.Constants
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState
import net.extrawdw.apps.locationhistory.data.db.LocationSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the detector's accuracy policy: precise fixes found/shape/break clusters; coarse fixes
 * (accuracy > the 60 m gate) only *sustain* a cluster they are consistent with — never found,
 * shift, or veto one. Repro from the 2026-06-12 device dump: an evening in Doze delivers ~1 h of
 * 100 m-accuracy Wi-Fi fixes 6 m from home, which the old all-or-nothing gate dropped entirely,
 * splitting the home stay across a synthetic >45 min "evidence gap".
 */
class VisitDetectorTest {

    private val detector = VisitDetector()
    private val t0 = 1_750_000_000_000L

    private fun sample(
        atMin: Long,
        lat: Double = 40.0,
        lon: Double = -74.0,
        accuracy: Float? = 12f,
        included: Boolean = true,
    ) = LocationSampleEntity(
        id = atMin, timestampMs = t0 + atMin * 60_000, dayEpoch = 0,
        latitude = lat, longitude = lon, altitude = null, accuracy = accuracy,
        verticalAccuracyMeters = null, bearing = null, bearingAccuracyDegrees = null,
        speed = 0f, speedAccuracyMetersPerSecond = null, provider = null, isMock = false,
        elapsedRealtimeNanos = 0, satelliteCount = null, batteryPct = null, isCharging = null,
        networkTransport = null, networkTypeName = null, cellSignalDbm = null,
        hasCellService = null, wifiSsid = null, wifiBssid = null, screenOn = null,
        arActivity = null, arConfidence = null,
        devicePhysicalState = DevicePhysicalState.STATIONARY,
        devicePhysicalStateConfidence = 0.8f,
        includedInComputation = included,
    )

    @Test
    fun coarseConsistentFixesSustainTheStay() {
        // The dump repro: precise fixes for 25 min, then ~65 min of coarse (100 m) fixes at the
        // same spot, then precise again. One stay, not two split across a fake evidence gap.
        val samples = buildList {
            for (m in 0L..24L step 3) add(sample(atMin = m))
            for (m in 28L..88L step 4) add(sample(atMin = m, accuracy = 100f))
            for (m in 90L..120L step 3) add(sample(atMin = m))
        }
        val visits = detector.detectVisits(samples)
        assertEquals(1, visits.size)
        assertTrue(visits[0].endMs - visits[0].startMs >= 120 * 60_000)
    }

    @Test
    fun coarseRunStillSubjectToEvidenceGap() {
        // Coarse fixes sustain, but a real >45 min hole with NO fixes at all still splits.
        val samples = buildList {
            for (m in 0L..10L step 2) add(sample(atMin = m))
            // nothing for 50 min
            for (m in 60L..70L step 2) add(sample(atMin = m))
        }
        assertEquals(2, detector.detectVisits(samples).size)
    }

    @Test
    fun coarseFixesCannotFoundACluster() {
        // Only coarse fixes: no cluster may form from them alone.
        val samples = (0L..30L step 3).map { sample(atMin = it, accuracy = 150f) }
        assertEquals(0, detector.detectVisits(samples).size)
    }

    @Test
    fun coarseFarFixesNeitherSustainNorVeto() {
        // A coarse fix far beyond its own accuracy doesn't break the cluster, and doesn't keep it
        // alive either: precise fixes resume within the gap, so the stay survives as one.
        val samples = buildList {
            for (m in 0L..12L step 3) add(sample(atMin = m))
            add(sample(atMin = 14, lat = 40.01, accuracy = 100f))   // ~1.1 km away, coarse
            for (m in 16L..28L step 3) add(sample(atMin = m))
        }
        val visits = detector.detectVisits(samples)
        assertEquals(1, visits.size)
        assertEquals(28 * 60_000, (visits[0].endMs - visits[0].startMs).toInt())
    }

    @Test
    fun preciseDepartureStillSplits() {
        // Two precise clusters ~2 km apart remain two visits.
        val samples = buildList {
            for (m in 0L..12L step 3) add(sample(atMin = m))
            for (m in 15L..27L step 3) add(sample(atMin = m, lat = 40.02))
        }
        assertEquals(2, detector.detectVisits(samples).size)
    }

    @Test
    fun excludedSamplesAreIgnored() {
        val samples = (0L..20L step 2).map { sample(atMin = it, included = false) }
        assertEquals(0, detector.detectVisits(samples).size)
    }

    @Test
    fun junkAccuracyCannotSustainFromAcrossTheNeighborhood() {
        // 500 m-accuracy fixes ~400 m away exceed the place-radius cap on the sustain allowance:
        // they neither sustain nor veto, so the >45 min hole splits the stay as a real gap.
        val samples = buildList {
            for (m in 0L..9L step 3) add(sample(atMin = m))
            for (m in 12L..56L step 4) add(sample(atMin = m, lat = 40.0036, accuracy = 500f))
            for (m in 60L..70L step 3) add(sample(atMin = m))
        }
        assertEquals(2, detector.detectVisits(samples).size)
    }

    // --- speed-aware carve (docs/doppler-timeline-findings.md) ---------------------------------

    /** A fix [sec] seconds into the window, with an explicit Doppler [speedV] (null = no GPS speed). */
    private fun at(
        sec: Long,
        lat: Double = 40.0,
        lon: Double = -74.0,
        accuracy: Float? = 12f,
        speedV: Float? = 0f,
    ) = LocationSampleEntity(
        id = sec, timestampMs = t0 + sec * 1000, dayEpoch = 0,
        latitude = lat, longitude = lon, altitude = null, accuracy = accuracy,
        verticalAccuracyMeters = null, bearing = null, bearingAccuracyDegrees = null,
        speed = speedV, speedAccuracyMetersPerSecond = null, provider = null, isMock = false,
        elapsedRealtimeNanos = 0, satelliteCount = null, batteryPct = null, isCharging = null,
        networkTransport = null, networkTypeName = null, cellSignalDbm = null,
        hasCellService = null, wifiSsid = null, wifiBssid = null, screenOn = null,
        arActivity = null, arConfidence = null,
        devicePhysicalState = DevicePhysicalState.STATIONARY,
        devicePhysicalStateConfidence = 0.8f, includedInComputation = true,
    )

    @Test
    fun sustainedDopplerRunCarvesATightWalkOutOfAStay() {
        // The June 14 case: a dense run of fixes carrying real Doppler speed but drifting only ~40 m,
        // so it never leaves the 60 m radius — geometry alone absorbs it. The run must split the stay.
        val samples = buildList {
            for (s in 0..6) add(at(s * 60L))                                       // home, still
            for (k in 0..23) add(at(420L + k * 10, lat = 40.0 + k * 0.000015, speedV = 1.0f)) // walk
            for (s in 0..7) add(at(780L + s * 60, lat = 40.0))                     // home again
        }
        // 2 visits = the walk was carved; with the old geometry-only detector this was 1 (absorbed).
        assertEquals(2, detector.detectVisits(samples).size)
    }

    @Test
    fun denseInPlaceDriftIsNotCarved() {
        // Same dense cadence and tight geometry, but Doppler ~0 (indoor scatter): no run forms, so it
        // stays ONE stay — the drift direction of the bug must not regress into a phantom split.
        val samples = buildList {
            for (s in 0..6) add(at(s * 60L))
            for (k in 0..23) add(at(420L + k * 10, lat = 40.0 + k * 0.000015, speedV = 0.2f))
            for (s in 0..7) add(at(780L + s * 60, lat = 40.0))
        }
        assertEquals(1, detector.detectVisits(samples).size)
    }

    @Test
    fun movingRuns_needSustainedValidSpeed() {
        // A dense run of valid >=0.8 m/s fixes is one moving run...
        assertEquals(1, movingRuns((0..23).map { at(it * 10L, speedV = 1.0f) }).size)
        // ...null speed never counts as moving (never folded to 0)...
        assertTrue(movingRuns((0..23).map { at(it * 10L, speedV = null) }).isEmpty())
        // ...sub-floor speed (<0.8) is not moving...
        assertTrue(movingRuns((0..23).map { at(it * 10L, speedV = 0.5f) }).isEmpty())
        // ...and a fast but sparse run (one fix/min) has too few fixes per window to judge.
        assertTrue(movingRuns((0..9).map { at(it * 60L, speedV = 1.0f) }).isEmpty())
    }

    @Test
    fun sustainExtendsEndToTheLastCoarseFix() {
        // Precise fixes end at minute 9; consistent coarse fixes continue to minute 40: the
        // stay's end must be the last sustaining fix, not the last precise one.
        val samples = buildList {
            for (m in 0L..9L step 3) add(sample(atMin = m))
            for (m in 12L..40L step 4) add(sample(atMin = m, accuracy = 100f))
        }
        val visits = detector.detectVisits(samples)
        assertEquals(1, visits.size)
        assertEquals(t0 + 40 * 60_000, visits[0].endMs)
        assertTrue(visits[0].sampleCount == 4)   // geometry from precise fixes only
    }
}
