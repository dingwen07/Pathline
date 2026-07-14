package net.extrawdw.apps.locationhistory.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateRepairDecision
import net.extrawdw.apps.locationhistory.core.PlaceCoordinateState
import net.extrawdw.apps.locationhistory.core.PlaceSource
import net.extrawdw.apps.locationhistory.core.coordinates.Gcj02CoordinateTransform
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateAdapter
import net.extrawdw.apps.locationhistory.core.coordinates.GoogleAndroidCoordinateProfiles
import net.extrawdw.apps.locationhistory.core.coordinates.GooglePlacesCoordinate
import net.extrawdw.apps.locationhistory.core.coordinates.MainlandChinaRegion
import net.extrawdw.apps.locationhistory.core.coordinates.getOrNull
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyPlaceCoordinateManagerTest {
    private lateinit var db: AppDatabase
    private lateinit var adapter: GoogleAndroidCoordinateAdapter
    private lateinit var manager: LegacyPlaceCoordinateManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        adapter = GoogleAndroidCoordinateAdapter(
            Gcj02CoordinateTransform(MainlandChinaRegion(context))
        )
        manager = LegacyPlaceCoordinateManager(
            db = db,
            placeDao = db.placeDao(),
            visitDao = db.visitDao(),
            repairDao = db.placeCoordinateRepairDao(),
            adapter = adapter,
            writeLock = TimelineWriteLock(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun outsideIdentityClassificationChangesOnlyStateAndIsJournaled() = runBlocking {
        val original = place(
            latitude = 1.352_083_234_567_89,
            longitude = 103.819_836_234_567_89,
            anchorLatitude = 1.352_073_234_567_89,
            anchorLongitude = 103.819_826_234_567_89,
        )
        val id = db.placeDao().insert(original)

        manager.classifySafeRows()

        val classified = requireNotNull(db.placeDao().byId(id))
        assertEquals(PlaceCoordinateState.WGS84_CANONICAL, classified.coordinateState)
        assertGeometryRawEquals(original.copy(id = id), classified)
        val journal = requireNotNull(db.placeCoordinateRepairDao().latestActive(id))
        assertEquals(
            PlaceCoordinateRepairDecision.AUTO_OUTSIDE_MAINLAND_IDENTITY,
            journal.decision,
        )

        // Automatic identity proof is not offered as a user undo: maintenance would immediately
        // re-apply it. The exact before-image remains in the encrypted journal for audit/recovery.
        assertFalse(manager.hasUndo(id))
        assertFalse(manager.undo(classified))
        assertEquals(
            PlaceCoordinateState.WGS84_CANONICAL,
            requireNotNull(db.placeDao().byId(id)).coordinateState,
        )
        assertFalse(manager.hasUndo(id))
    }

    @Test
    fun historicalBaselineRepairNeverInverseTransformsMixedCenter() = runBlocking {
        val original = place(
            latitude = 31.229_100_123,
            longitude = 121.475_900_456,
            anchorLatitude = 31.228_457_738,
            anchorLongitude = 121.478_223_059,
        ).copy(
            coordinateState = PlaceCoordinateState.LEGACY_MIXED_CENTER_GOOGLE_MAP_BASELINE,
            source = PlaceSource.USER,
            googlePlaceId = null,
        )
        val id = db.placeDao().insert(original)
        val persisted = requireNotNull(db.placeDao().byId(id))

        manager.classifySafeRows()
        assertEquals(
            PlaceCoordinateState.LEGACY_MIXED_CENTER_GOOGLE_MAP_BASELINE,
            requireNotNull(db.placeDao().byId(id)).coordinateState,
        )

        assertTrue(
            manager.repair(
                persisted,
                PlaceCoordinateRepairDecision.GOOGLE_BASELINE_AS_HISTORICAL_PROVIDER,
            )
        )
        val repaired = requireNotNull(db.placeDao().byId(id))
        val expected = requireNotNull(
            adapter.fromPlacesResult(
                GooglePlacesCoordinate(original.anchorLatitude!!, original.anchorLongitude!!),
                GoogleAndroidCoordinateProfiles
                    .HISTORICAL_PLACES_ANDROID_5_2_MAINLAND_2026_07,
            ).getOrNull()
        )
        val forbiddenMixedInverse = requireNotNull(
            adapter.fromPlacesResult(
                GooglePlacesCoordinate(original.latitude, original.longitude),
                GoogleAndroidCoordinateProfiles
                    .HISTORICAL_PLACES_ANDROID_5_2_MAINLAND_2026_07,
            ).getOrNull()
        )

        assertEquals(PlaceCoordinateState.WGS84_CANONICAL, repaired.coordinateState)
        assertEquals(PlaceSource.MAPS, repaired.source)
        assertEquals(expected.latitude, repaired.latitude, 1e-7)
        assertEquals(expected.longitude, repaired.longitude, 1e-7)
        assertNotEquals(forbiddenMixedInverse.latitude, repaired.latitude, 1e-7)
        assertNotEquals(forbiddenMixedInverse.longitude, repaired.longitude, 1e-7)
        assertRawEquals(original.radiusMeters, repaired.radiusMeters)
        val journal = requireNotNull(db.placeCoordinateRepairDao().latestActive(id))
        assertEquals(PlaceSource.USER, journal.originalSource)
        assertEquals(PlaceSource.MAPS, journal.repairedSource)
        assertTrue(manager.undo(repaired))
        val restored = requireNotNull(db.placeDao().byId(id))
        assertGeometryRawEquals(original.copy(id = id), restored)
        assertEquals(PlaceSource.USER, restored.source)
    }

    @Test
    fun wgsBaselineRepairPersistsProviderAuthorityAndUndoRestoresSource() = runBlocking {
        val original = place(
            latitude = 31.229_100_123,
            longitude = 121.475_900_456,
            anchorLatitude = 31.228_457_738,
            anchorLongitude = 121.478_223_059,
        ).copy(
            source = PlaceSource.USER,
            googlePlaceId = null,
        )
        val id = db.placeDao().insert(original)
        val persisted = requireNotNull(db.placeDao().byId(id))

        assertTrue(
            manager.repair(
                persisted,
                PlaceCoordinateRepairDecision.GOOGLE_BASELINE_AS_WGS84,
            )
        )
        val repaired = requireNotNull(db.placeDao().byId(id))
        assertEquals(PlaceSource.MAPS, repaired.source)
        assertRawEquals(original.anchorLatitude!!, repaired.latitude)
        assertRawEquals(original.anchorLongitude!!, repaired.longitude)
        val journal = requireNotNull(db.placeCoordinateRepairDao().latestActive(id))
        assertEquals(PlaceSource.USER, journal.originalSource)
        assertEquals(PlaceSource.MAPS, journal.repairedSource)

        assertTrue(manager.undo(repaired))
        val restored = requireNotNull(db.placeDao().byId(id))
        assertGeometryRawEquals(original.copy(id = id), restored)
        assertEquals(PlaceSource.USER, restored.source)
    }

    @Test
    fun mixedCenterRejectsSavedCenterRepairsAndPreservesRawGeometry() = runBlocking {
        val original = place(
            latitude = 31.229_100_123,
            longitude = 121.475_900_456,
            anchorLatitude = 31.228_457_738,
            anchorLongitude = 121.478_223_059,
        ).copy(
            coordinateState = PlaceCoordinateState.LEGACY_MIXED_CENTER_GOOGLE_MAP_BASELINE,
        )
        val id = db.placeDao().insert(original)
        val persisted = requireNotNull(db.placeDao().byId(id))

        assertFalse(
            manager.repair(
                persisted,
                PlaceCoordinateRepairDecision.SAVED_CENTER_AS_HISTORICAL_MAP,
            )
        )
        assertGeometryRawEquals(original.copy(id = id), requireNotNull(db.placeDao().byId(id)))

        assertFalse(
            manager.repair(
                persisted,
                PlaceCoordinateRepairDecision.SAVED_CENTER_AS_WGS84,
            )
        )
        assertGeometryRawEquals(original.copy(id = id), requireNotNull(db.placeDao().byId(id)))
    }

    private fun place(
        latitude: Double,
        longitude: Double,
        anchorLatitude: Double,
        anchorLongitude: Double,
    ) = PlaceEntity(
        name = "Legacy test place",
        latitude = latitude,
        longitude = longitude,
        radiusMeters = 57.123_456_789,
        category = null,
        source = PlaceSource.MAPS,
        googlePlaceId = "legacy-test-id",
        address = null,
        confirmed = true,
        createdAtMs = 0L,
        anchorLatitude = anchorLatitude,
        anchorLongitude = anchorLongitude,
        anchorRadiusMeters = 42.987_654_321,
        coordinateState = PlaceCoordinateState.UNKNOWN,
    )

    private fun assertGeometryRawEquals(expected: PlaceEntity, actual: PlaceEntity) {
        assertRawEquals(expected.latitude, actual.latitude)
        assertRawEquals(expected.longitude, actual.longitude)
        assertRawEquals(expected.radiusMeters, actual.radiusMeters)
        assertRawEquals(expected.anchorLatitude!!, actual.anchorLatitude!!)
        assertRawEquals(expected.anchorLongitude!!, actual.anchorLongitude!!)
        assertRawEquals(expected.anchorRadiusMeters!!, actual.anchorRadiusMeters!!)
    }

    private fun assertRawEquals(expected: Double, actual: Double) {
        assertEquals(expected.toRawBits(), actual.toRawBits())
    }
}
