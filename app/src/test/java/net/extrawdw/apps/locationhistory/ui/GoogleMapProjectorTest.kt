package net.extrawdw.apps.locationhistory.ui

import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.coordinates.Gcj02CoordinateTransform
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateAdapter
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleMapCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.MainlandRegionClassifier
import net.extrawdw.apps.locationhistory.core.coordinates.Wgs84Coordinate
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleMapProjectorTest {
    @Test
    fun canonicalPlace_isForwardProjectedExactlyOnce() {
        val projector = projector(mainlandEverywhere)
        val place = place(
            latitude = BEIJING_WGS.latitude,
            longitude = BEIJING_WGS.longitude,
            coordinateState = PlaceCoordinateState.WGS84_CANONICAL,
        )

        val projected = requireNotNull(projector.placeCircle(place))

        assertTrue(projected.isCanonical)
        assertRawEquals(BEIJING_WGS.latitude, projected.canonicalCenter.latitude)
        assertRawEquals(BEIJING_WGS.longitude, projected.canonicalCenter.longitude)
        assertEquals(BEIJING_GCJ.latitude, projected.circle.center.latitude, 1e-9)
        assertEquals(BEIJING_GCJ.longitude, projected.circle.center.longitude, 1e-9)
    }

    @Test
    fun legacyGooglePlace_keepsProviderBaselineWithoutASecondForwardProjection() {
        val projector = projector(mainlandEverywhere)
        val projectedAgain = requireNotNull(
            projector.coordinate(Wgs84Coordinate(BEIJING_GCJ.latitude, BEIJING_GCJ.longitude))
        )
        assertFalse(rawEquals(BEIJING_GCJ, projectedAgain))

        listOf(
            PlaceCoordinateState.LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE,
            PlaceCoordinateState.LEGACY_MIXED_CENTER_GOOGLE_MAP_BASELINE,
        ).forEach { state ->
            val place = place(
                // Deliberately different: legacy mixed centers must not replace the provider anchor.
                latitude = BEIJING_WGS.latitude + 0.001,
                longitude = BEIJING_WGS.longitude + 0.001,
                coordinateState = state,
                anchor = BEIJING_GCJ,
            )

            assertNull(projector.placeCircle(place))
            val projected = requireNotNull(projector.placePreviewCircle(place))

            assertFalse(projected.isCanonical)
            assertRawEquals(BEIJING_GCJ.latitude, projected.circle.center.latitude)
            assertRawEquals(BEIJING_GCJ.longitude, projected.circle.center.longitude)
            assertEquals(BEIJING_WGS.latitude, projected.canonicalCenter.latitude, 1e-7)
            assertEquals(BEIJING_WGS.longitude, projected.canonicalCenter.longitude, 1e-7)
        }
    }

    @Test
    fun legacyProviderBaselinePreviewDoesNotTrustOldSourceMetadata() {
        val projector = projector(mainlandEverywhere)
        val place = place(
            latitude = BEIJING_WGS.latitude + 0.001,
            longitude = BEIJING_WGS.longitude + 0.001,
            coordinateState = PlaceCoordinateState.UNKNOWN,
            anchor = BEIJING_GCJ,
            source = PlaceSource.USER,
            googlePlaceId = null,
        )

        val projected = requireNotNull(projector.placePreviewCircle(place))

        assertFalse(projected.isCanonical)
        assertRawEquals(BEIJING_GCJ.latitude, projected.circle.center.latitude)
        assertRawEquals(BEIJING_GCJ.longitude, projected.circle.center.longitude)
    }

    @Test
    fun outsideMainland_projectorDirectionsAreBitExactIdentity() {
        val projector = projector(mainlandNowhere)
        val wgs = Wgs84Coordinate(1.352083, 103.819836)

        val map = requireNotNull(projector.coordinate(wgs))
        val recovered = requireNotNull(projector.fromMapForPreview(map))

        assertRawEquals(wgs.latitude, map.latitude)
        assertRawEquals(wgs.longitude, map.longitude)
        assertRawEquals(wgs.latitude, recovered.latitude)
        assertRawEquals(wgs.longitude, recovered.longitude)
        assertNull(projector.fromMap(map)) // click ingress is deliberately unverified/write-disabled
    }

    private fun projector(mainland: MainlandRegionClassifier): GoogleMapProjector =
        GoogleMapProjector(
            GoogleAndroidCoordinateAdapter(Gcj02CoordinateTransform(mainland))
        )

    private fun place(
        latitude: Double,
        longitude: Double,
        coordinateState: PlaceCoordinateState,
        anchor: GoogleMapCoordinate? = null,
        source: PlaceSource = PlaceSource.MAPS,
        googlePlaceId: String? = "fixture-id",
    ) = PlaceEntity(
        name = "Fixture place",
        latitude = latitude,
        longitude = longitude,
        radiusMeters = 50.0,
        category = null,
        source = source,
        googlePlaceId = googlePlaceId,
        address = null,
        confirmed = true,
        createdAtMs = 0L,
        anchorLatitude = anchor?.latitude,
        anchorLongitude = anchor?.longitude,
        coordinateState = coordinateState,
    )

    private fun assertRawEquals(expected: Double, actual: Double) {
        assertEquals(expected.toRawBits(), actual.toRawBits())
    }

    private fun rawEquals(expected: GoogleMapCoordinate, actual: GoogleMapCoordinate): Boolean =
        expected.latitude.toRawBits() == actual.latitude.toRawBits() &&
                expected.longitude.toRawBits() == actual.longitude.toRawBits()

    private companion object {
        val BEIJING_WGS = Wgs84Coordinate(39.908823, 116.397470)
        val BEIJING_GCJ = GoogleMapCoordinate(39.910226498, 116.403713582)

        val mainlandEverywhere = classifier(contains = true)
        val mainlandNowhere = classifier(contains = false)

        fun classifier(contains: Boolean): MainlandRegionClassifier =
            object : MainlandRegionClassifier {
                override fun contains(latitude: Double, longitude: Double): Boolean = contains

                override fun mightContain(
                    latitude: Double,
                    longitude: Double,
                    marginDegrees: Double,
                ): Boolean = contains
            }
    }
}
