package net.extrawdw.apps.locationhistory.data.places

import net.extrawdw.apps.locationhistory.core.coordinates.Gcj02CoordinateTransform
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateAdapter
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleBoundaryFrame
import net.extrawdw.apps.locationhistory.core.coordinates.GooglePlacesCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.MainlandRegionClassifier
import net.extrawdw.apps.locationhistory.core.coordinates.TransformResult
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesCoordinateBoundaryTest {
    private val mainlandEverywhere = object : MainlandRegionClassifier {
        override fun contains(latitude: Double, longitude: Double): Boolean = true

        override fun mightContain(
            latitude: Double,
            longitude: Double,
            marginDegrees: Double,
        ): Boolean = true
    }

    @Test
    fun canonicalRankingDoesNotCompareWgsCenterWithProviderCoordinates() {
        val adapter = GoogleAndroidCoordinateAdapter(
            Gcj02CoordinateTransform(mainlandEverywhere)
        )
        val center = Wgs84Coordinate(39.908823, 116.397470)
        // The farther point lies west, opposite the local GCJ displacement. Comparing the raw
        // provider numbers to the WGS center would rank it first; canonical ranking must not.
        val canonical = listOf(
            "near" to Wgs84Coordinate(center.latitude, center.longitude + 0.0009),
            "far" to Wgs84Coordinate(center.latitude, center.longitude - 0.0030),
        )
        val provider = canonical.map { (name, coordinate) ->
            val projected = adapter.toPlacesRequest(coordinate).success()
            name to GooglePlacesCoordinate(projected.latitude, projected.longitude)
        }

        val ranked = normalizeAndRankPlaces(
            center = center,
            values = provider,
            coordinateAdapter = adapter,
            operationProfile = adapter.profile,
            providerCoordinate = { it.second },
        )

        assertEquals(listOf("near", "far"), ranked.map { it.value.first })
        assertEquals(canonical[0].second.latitude, ranked[0].coordinate.latitude, 1e-7)
        assertEquals(canonical[0].second.longitude, ranked[0].coordinate.longitude, 1e-7)
        assertTrue(ranked[0].distanceMeters < ranked[1].distanceMeters)
    }

    @Test
    fun unverifiedResultFrameProducesNoCanonicalCandidates() {
        val adapter = GoogleAndroidCoordinateAdapter(
            Gcj02CoordinateTransform(mainlandEverywhere)
        )
        val profile = adapter.profile.copy(placesResultOutput = GoogleBoundaryFrame.UNVERIFIED)

        val ranked = normalizeAndRankPlaces(
            center = Wgs84Coordinate(39.908823, 116.397470),
            values = listOf(GooglePlacesCoordinate(39.91, 116.40)),
            coordinateAdapter = adapter,
            operationProfile = profile,
            providerCoordinate = { it },
        )

        assertTrue(ranked.isEmpty())
    }

    private fun <T> TransformResult<T>.success(): T =
        (this as TransformResult.Success<T>).coordinate
}
