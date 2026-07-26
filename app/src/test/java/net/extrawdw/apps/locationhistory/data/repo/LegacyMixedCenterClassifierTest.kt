package net.extrawdw.apps.locationhistory.data.repo

import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMixedCenterClassifierTest {
    @Test
    fun suppliedHangzhouBlend_isProvablyHistoricalMixedCenter() {
        assertTrue(LegacyMixedCenterClassifier.isProvable(HANGZHOU_PLACE, listOf(HANGZHOU_VISIT)))
    }

    @Test
    fun matchingCenterLineWithWrongRadius_isNotClassified() {
        assertFalse(
            LegacyMixedCenterClassifier.isProvable(
                HANGZHOU_PLACE.copy(radiusMeters = HANGZHOU_PLACE.radiusMeters + 2.0),
                listOf(HANGZHOU_VISIT),
            )
        )
    }

    @Test
    fun fixedOrNonGooglePlace_isNotClassified() {
        assertFalse(
            LegacyMixedCenterClassifier.isProvable(
                HANGZHOU_PLACE.copy(fixed = true),
                listOf(HANGZHOU_VISIT),
            )
        )
        assertFalse(
            LegacyMixedCenterClassifier.isProvable(
                HANGZHOU_PLACE.copy(source = PlaceSource.USER),
                listOf(HANGZHOU_VISIT),
            )
        )
    }

    private companion object {
        val HANGZHOU_PLACE = PlaceEntity(
            id = 21,
            name = "文菁花苑2幢",
            latitude = 30.289257161716655,
            longitude = 120.12435028996394,
            radiusMeters = 44.7806044617915,
            category = null,
            source = PlaceSource.MAPS,
            googlePlaceId = "ChIJCY5CT0ViSzQRsTqrY8Fi5ck",
            address = null,
            confirmed = true,
            createdAtMs = 0L,
            anchorLatitude = 30.288797399999996,
            anchorLongitude = 120.12530039999999,
            anchorRadiusMeters = 50.0,
            coordinateState = PlaceCoordinateState.UNKNOWN,
        )

        val HANGZHOU_VISIT = VisitEntity(
            id = 13_888,
            placeId = 21,
            candidateName = null,
            candidateGooglePlaceId = null,
            candidateLatitude = null,
            candidateLongitude = null,
            startMs = 1_783_932_842_018L,
            endMs = 1_783_976_400_810L,
            dayEpoch = 20_647L,
            centroidLatitude = 30.29099957893666,
            centroidLongitude = 120.12074953760076,
            radiusMeters = 25.0,
            sampleCount = 1,
            reliability = 0.67308307f,
            confirmed = true,
            confidence = 1f,
            isOngoing = false,
        )
    }
}
