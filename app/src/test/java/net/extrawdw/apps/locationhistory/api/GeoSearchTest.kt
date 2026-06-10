package net.extrawdw.apps.locationhistory.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure-geometry tests for the proximity mode's parsing, bounding box and Haversine. */
class GeoSearchTest {

    // --- parseNear -------------------------------------------------------------------------------

    @Test
    fun parseNear_acceptsLatLngAndTrims() {
        assertEquals(40.7235 to -74.0354, GeoSearch.parseNear("40.7235,-74.0354"))
        assertEquals(40.0 to 74.0, GeoSearch.parseNear(" 40 , 74 "))
    }

    @Test
    fun parseNear_rejectsMalformed() {
        for (bad in listOf("", "40", "40,", "a,b", "91,0", "-91,0", "0,181", "0,-181", "1,2,3", "NaN,0")) {
            try {
                GeoSearch.parseNear(bad)
                fail("expected rejection of '$bad'")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    // --- parseRadius -----------------------------------------------------------------------------

    @Test
    fun parseRadius_defaultsClampsAndRejects() {
        assertEquals(GeoSearch.DEFAULT_RADIUS_M, GeoSearch.parseRadius(null), 0.0)
        assertEquals(250.0, GeoSearch.parseRadius("250"), 0.0)
        // Oversized asks clamp, they don't error.
        assertEquals(GeoSearch.MAX_RADIUS_M, GeoSearch.parseRadius("999999999"), 0.0)
        for (bad in listOf("0", "-5", "abc", "")) {
            try {
                GeoSearch.parseRadius(bad)
                fail("expected rejection of '$bad'")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    // --- boundingBox -----------------------------------------------------------------------------

    @Test
    fun boundingBox_containsTheRadiusCircle() {
        // Jersey City, 500 m: every point of the circle must fall inside the box.
        val box = GeoSearch.boundingBox(40.7235, -74.0354, 500.0)
        assertFalse(box.wrapsAntimeridian)
        // The cardinal extremes of the circle (approx 500 m in degrees).
        assertTrue(box.latMax >= 40.7235 + 500.0 / 111_320.0 - 1e-9)
        assertTrue(box.latMin <= 40.7235 - 500.0 / 111_320.0 + 1e-9)
        // Longitude half-width must be WIDER than the equatorial 500 m (cos correction).
        val equatorialHalf = 500.0 / 111_320.0
        assertTrue((box.lngMax - (-74.0354)) > equatorialHalf)
    }

    @Test
    fun boundingBox_widensTowardThePoles_andClampsLatitude() {
        val mid = GeoSearch.boundingBox(40.0, 0.0, 1000.0)
        val far = GeoSearch.boundingBox(80.0, 0.0, 1000.0)
        assertTrue((far.lngMax - far.lngMin) > (mid.lngMax - mid.lngMin))
        // Near the pole the latitude edge clamps to 90 rather than overshooting.
        val polar = GeoSearch.boundingBox(89.9999, 0.0, 50_000.0)
        assertEquals(90.0, polar.latMax, 0.0)
    }

    @Test
    fun boundingBox_flagsAntimeridianWrap() {
        assertTrue(GeoSearch.boundingBox(0.0, 179.9999, 1000.0).wrapsAntimeridian)
        assertTrue(GeoSearch.boundingBox(0.0, -179.9999, 1000.0).wrapsAntimeridian)
        assertFalse(GeoSearch.boundingBox(0.0, 0.0, 1000.0).wrapsAntimeridian)
    }

    // --- haversine -------------------------------------------------------------------------------

    @Test
    fun haversine_matchesKnownDistances() {
        // Same point.
        assertEquals(0.0, GeoSearch.haversineMeters(40.0, -74.0, 40.0, -74.0), 0.001)
        // One degree of latitude is ~111.2 km everywhere.
        assertEquals(111_195.0, GeoSearch.haversineMeters(40.0, -74.0, 41.0, -74.0), 200.0)
        // Exchange Place light rail -> Apartment 5129 (the test fixture's town): ~1.6 km.
        val d = GeoSearch.haversineMeters(40.7166, -74.0337, 40.7235, -74.0354)
        assertTrue("got $d", d in 700.0..900.0)
    }

    @Test
    fun haversine_pairsWithBoundingBox() {
        // Anything Haversine keeps within radius must be inside the prefilter box too.
        val lat = 40.7235; val lng = -74.0354; val r = 500.0
        val box = GeoSearch.boundingBox(lat, lng, r)
        for ((pLat, pLng) in listOf(40.7270 to -74.0354, 40.7235 to -74.0300, 40.7260 to -74.0320)) {
            if (GeoSearch.haversineMeters(lat, lng, pLat, pLng) <= r) {
                assertTrue(pLat in box.latMin..box.latMax)
                assertTrue(pLng in box.lngMin..box.lngMax)
            }
        }
    }
}
