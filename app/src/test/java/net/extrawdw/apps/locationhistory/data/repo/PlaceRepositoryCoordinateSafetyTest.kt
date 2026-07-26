package net.extrawdw.apps.locationhistory.data.repo

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import net.extrawdw.apps.locationhistory.domain.FakeAnnotationDao
import net.extrawdw.apps.locationhistory.domain.FakeConceptDao
import net.extrawdw.apps.locationhistory.domain.FakePlaceDao
import net.extrawdw.apps.locationhistory.domain.FakeTagDao
import net.extrawdw.apps.locationhistory.domain.FakeVisitDao
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceRepositoryCoordinateSafetyTest {
    private val placeDao = FakePlaceDao()
    private val repository = PlaceRepository(
        placeDao,
        FakeVisitDao(),
        AnnotationStore(FakeTagDao(), FakeAnnotationDao(), FakeConceptDao()),
    )

    @Test
    fun centerOnlyEditPreservesDoubleRadiusExactly() = runBlocking {
        val original = place(
            coordinateState = PlaceCoordinateState.WGS84_CANONICAL,
            radius = 47.123_456_789_012_3,
        )
        placeDao.seed(original)
        val persisted = placeDao.places.single()

        repository.updateFromEditor(
            original = persisted,
            name = persisted.name,
            address = persisted.address,
            latitude = 31.231,
            longitude = 121.474,
            // This is what an untouched Compose Slider would expose; it must be ignored.
            radiusMeters = persisted.radiusMeters.toFloat().toDouble(),
            fixed = persisted.fixed,
            centerChanged = true,
            radiusChanged = false,
        )

        val actual = placeDao.places.single()
        assertRawEquals(persisted.radiusMeters, actual.radiusMeters)
        assertRawEquals(persisted.anchorRadiusMeters!!, actual.anchorRadiusMeters!!)
        assertRawEquals(31.231, actual.latitude)
        assertRawEquals(121.474, actual.longitude)
        assertRawEquals(31.231, actual.anchorLatitude!!)
        assertRawEquals(121.474, actual.anchorLongitude!!)
    }

    @Test
    fun legacyRadiusEditRefreshesOnlyFrameIndependentRadiusBaseline() = runBlocking {
        val original = place(coordinateState = PlaceCoordinateState.UNKNOWN, radius = 47.25)
        placeDao.seed(original)
        val persisted = placeDao.places.single()

        repository.updateFromEditor(
            original = persisted,
            name = persisted.name,
            address = persisted.address,
            latitude = 0.0,
            longitude = 0.0,
            radiusMeters = 88.5,
            fixed = true,
            centerChanged = true, // a stale/malicious unresolved editor cannot promote or move it
            radiusChanged = true,
        )

        val actual = placeDao.places.single()
        assertRawEquals(persisted.latitude, actual.latitude)
        assertRawEquals(persisted.longitude, actual.longitude)
        assertRawEquals(persisted.anchorLatitude!!, actual.anchorLatitude!!)
        assertRawEquals(persisted.anchorLongitude!!, actual.anchorLongitude!!)
        assertRawEquals(88.5, actual.radiusMeters)
        assertRawEquals(88.5, actual.anchorRadiusMeters!!)
        assertEquals(PlaceCoordinateState.UNKNOWN, actual.coordinateState)
    }

    private fun place(coordinateState: PlaceCoordinateState, radius: Double) = PlaceEntity(
        name = "Test place",
        latitude = 31.2304,
        longitude = 121.4737,
        radiusMeters = radius,
        category = null,
        source = PlaceSource.MAPS,
        googlePlaceId = "test-id",
        address = null,
        confirmed = true,
        createdAtMs = 0L,
        anchorLatitude = 31.2304,
        anchorLongitude = 121.4737,
        anchorRadiusMeters = radius - 1.0,
        coordinateState = coordinateState,
    )

    private fun assertRawEquals(expected: Double, actual: Double) {
        assertEquals(expected.toRawBits(), actual.toRawBits())
    }
}
