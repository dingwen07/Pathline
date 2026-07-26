package net.extrawdw.apps.locationhistory.core.coordinates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gcj02CoordinateTransformTest {
    private val mainlandEverywhere = object : MainlandRegionClassifier {
        override fun contains(latitude: Double, longitude: Double): Boolean = true
        override fun mightContain(
            latitude: Double,
            longitude: Double,
            marginDegrees: Double,
        ): Boolean = true
    }
    private val mainlandNowhere = object : MainlandRegionClassifier {
        override fun contains(latitude: Double, longitude: Double): Boolean = false
        override fun mightContain(
            latitude: Double,
            longitude: Double,
            marginDegrees: Double,
        ): Boolean = false
    }

    @Test
    fun forward_matchesPublicFormulaFixtures() {
        val transform = Gcj02CoordinateTransform(mainlandEverywhere)
        val fixtures = listOf(
            Fixture(39.908823, 116.397470, 39.910226498, 116.403713582),
            Fixture(31.230400, 121.473700, 31.228457738, 121.478223059),
            Fixture(23.129100, 113.264400, 23.126423340, 113.269729592),
            Fixture(30.572800, 104.066800, 30.570346141, 104.069305477),
            Fixture(43.825600, 87.616800, 43.826805393, 87.619649949),
            Fixture(45.803800, 126.534900, 45.805781775, 126.540940717),
            Fixture(18.252800, 109.511900, 18.251094792, 109.515984295),
        )
        fixtures.forEach { fixture ->
            val actual = transform.wgs84ToGcj02(
                Wgs84Coordinate(fixture.wgsLatitude, fixture.wgsLongitude)
            ).success()
            assertEquals(fixture.gcjLatitude, actual.latitude, 1e-9)
            assertEquals(fixture.gcjLongitude, actual.longitude, 1e-9)
        }
    }

    @Test
    fun inverse_recoversOriginalThroughSameForwardKernel() {
        val transform = Gcj02CoordinateTransform(mainlandEverywhere)
        val original = Wgs84Coordinate(39.908823, 116.397470)
        val projected = transform.wgs84ToGcj02(original).success()
        val recovered = transform.gcj02ToWgs84(projected).success()

        assertEquals(original.latitude, recovered.latitude, 1e-7)
        assertEquals(original.longitude, recovered.longitude, 1e-7)
    }

    @Test
    fun outsideMask_isBitExactIdentityInBothDirections() {
        val transform = Gcj02CoordinateTransform(mainlandNowhere)
        val latitude = 1.352083
        val longitude = 103.819836

        val forward = transform.wgs84ToGcj02(Wgs84Coordinate(latitude, longitude)).success()
        val inverse = transform.gcj02ToWgs84(Gcj02Coordinate(latitude, longitude)).success()

        assertEquals(latitude.toRawBits(), forward.latitude.toRawBits())
        assertEquals(longitude.toRawBits(), forward.longitude.toRawBits())
        assertEquals(latitude.toRawBits(), inverse.latitude.toRawBits())
        assertEquals(longitude.toRawBits(), inverse.longitude.toRawBits())
    }

    @Test
    fun invalidInputs_failDeterministically() {
        val transform = Gcj02CoordinateTransform(mainlandEverywhere)
        assertEquals(
            TransformResult.Reason.INVALID_INPUT,
            (transform.wgs84ToGcj02(Wgs84Coordinate(Double.NaN, 0.0)) as
                    TransformResult.Failure).reason,
        )
        assertEquals(
            TransformResult.Reason.OUTSIDE_SUPPORTED_RANGE,
            (transform.gcj02ToWgs84(Gcj02Coordinate(91.0, 0.0)) as
                    TransformResult.Failure).reason,
        )
    }

    @Test
    fun adapter_keepsDirectionsIndependentAndFailsClosedWhenUnverified() {
        val adapter = GoogleAndroidCoordinateAdapter(
            Gcj02CoordinateTransform(mainlandEverywhere)
        )
        val profile = adapter.profile.copy(
            mapRenderInput = GoogleBoundaryFrame.WGS84,
            placesRequestInput = GoogleBoundaryFrame.UNVERIFIED,
            placesResultOutput = GoogleBoundaryFrame.MAINLAND_GCJ02,
        )
        val wgs = Wgs84Coordinate(39.908823, 116.397470)

        val map = adapter.toMap(wgs, profile).success()
        assertEquals(wgs.latitude.toRawBits(), map.latitude.toRawBits())
        assertTrue(
            adapter.toPlacesRequest(wgs, profile) is TransformResult.Failure
        )
        assertTrue(
            adapter.fromPlacesResult(
                GooglePlacesCoordinate(wgs.latitude, wgs.longitude),
                profile.copy(placesResultOutput = GoogleBoundaryFrame.UNVERIFIED),
            ) is TransformResult.Failure
        )
    }

    @Test
    fun adapter_placesRequestAndResultUseOppositeDirectionsAndRoundTrip() {
        val adapter = GoogleAndroidCoordinateAdapter(
            Gcj02CoordinateTransform(mainlandEverywhere)
        )
        val original = Wgs84Coordinate(39.908823, 116.397470)

        val request = adapter.toPlacesRequest(original).success()
        assertEquals(39.910226498, request.latitude, 1e-9)
        assertEquals(116.403713582, request.longitude, 1e-9)

        val normalized = adapter.fromPlacesResult(request).success()
        assertEquals(original.latitude, normalized.latitude, 1e-7)
        assertEquals(original.longitude, normalized.longitude, 1e-7)
    }

    @Test
    fun adapter_placesDirectionsAreBitExactOutsideMainland() {
        val adapter = GoogleAndroidCoordinateAdapter(
            Gcj02CoordinateTransform(mainlandNowhere)
        )
        val original = Wgs84Coordinate(1.352083, 103.819836)

        val request = adapter.toPlacesRequest(original).success()
        val normalized = adapter.fromPlacesResult(request).success()

        assertEquals(original.latitude.toRawBits(), request.latitude.toRawBits())
        assertEquals(original.longitude.toRawBits(), request.longitude.toRawBits())
        assertEquals(original.latitude.toRawBits(), normalized.latitude.toRawBits())
        assertEquals(original.longitude.toRawBits(), normalized.longitude.toRawBits())
    }

    private fun <T> TransformResult<T>.success(): T =
        (this as TransformResult.Success<T>).coordinate

    private data class Fixture(
        val wgsLatitude: Double,
        val wgsLongitude: Double,
        val gcjLatitude: Double,
        val gcjLongitude: Double,
    )
}
