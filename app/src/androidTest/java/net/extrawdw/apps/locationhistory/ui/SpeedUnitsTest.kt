package net.extrawdw.apps.locationhistory.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedUnitsTest {
    @Test fun speedUsesSystemRegionalUnits() {
        assertEquals("22.4 mph", speed("en-US"))
        assertEquals("36 km/h", speed("en-SG"))
    }

    @Test fun explicitMeasurementPreferenceOverridesRegion() {
        assertEquals("36 km/h", speed("en-US-u-ms-metric"))
        assertEquals("22.4 mph", speed("en-SG-u-ms-ussystem"))
    }

    @Test fun appLanguageDoesNotChangeSystemSpeedUnits() {
        val result = Format.speed(10.0, Locale.US, Locale.GERMANY)
        assertTrue(result.startsWith("22,4"))
    }

    @Test fun altitudeKeepsMetersOrFeetAcrossTheProfileAndHonorsSystemOverrides() {
        fun altitude(meters: Double, tag: String) =
            Format.altitude(meters, Locale.forLanguageTag(tag), Locale.US)
                .replace('\u00a0', ' ').replace('\u202f', ' ')
        assertEquals("328 ft", altitude(100.0, "en-US"))
        assertEquals("100 m", altitude(100.0, "en-SG"))
        assertEquals("-10 m", altitude(-10.0, "en-US-u-ms-metric"))
        assertEquals("3,281 ft", altitude(1000.0, "en-SG-u-ms-ussystem"))
        assertEquals("0 ft", altitude(0.0, "en-US"))
    }

    private fun speed(tag: String) = Format.speed(10.0, Locale.forLanguageTag(tag), Locale.US)
        .replace('\u00a0', ' ').replace('\u202f', ' ')
}
