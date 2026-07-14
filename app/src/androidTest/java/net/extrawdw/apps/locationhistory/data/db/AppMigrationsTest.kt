package net.extrawdw.apps.locationhistory.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppMigrationsTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migration2To3_preservesGeometry_classifiesOnlyExactMapsBaseline_andClearsCandidates() {
        helper.createDatabase(TEST_DATABASE, 2).use { database ->
            PLACES.forEach { place -> database.insertPlace(place) }
            database.insertVisit(VISIT)
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            3,
            true,
            AppMigrations.MIGRATION_2_3,
        ).use { database ->
            assertPlaces(database)
            assertRepairJournal(database)
            assertVisit(database)
        }
    }

    private fun assertPlaces(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT id, latitude, longitude, radiusMeters, anchorLatitude, anchorLongitude, " +
                "anchorRadiusMeters, coordinateState FROM places ORDER BY id",
        ).use { cursor ->
            PLACES.forEach { expected ->
                assertTrue("missing place ${expected.id}", cursor.moveToNext())
                assertEquals(expected.id, cursor.getLong(0))
                assertDoubleUnchanged(
                    "place ${expected.id} latitude",
                    expected.latitude,
                    cursor.getDouble(1),
                )
                assertDoubleUnchanged(
                    "place ${expected.id} longitude",
                    expected.longitude,
                    cursor.getDouble(2),
                )
                assertDoubleUnchanged(
                    "place ${expected.id} radius",
                    expected.radiusMeters,
                    cursor.getDouble(3),
                )
                assertDoubleUnchanged(
                    "place ${expected.id} anchor latitude",
                    expected.anchorLatitude,
                    cursor.getDouble(4),
                )
                assertDoubleUnchanged(
                    "place ${expected.id} anchor longitude",
                    expected.anchorLongitude,
                    cursor.getDouble(5),
                )
                assertDoubleUnchanged(
                    "place ${expected.id} anchor radius",
                    expected.anchorRadiusMeters,
                    cursor.getDouble(6),
                )
                assertEquals(expected.coordinateState, cursor.getString(7))
            }
            assertFalse("unexpected extra places", cursor.moveToNext())
        }
    }

    private fun assertVisit(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT id, placeId, candidateName, candidateGooglePlaceId, candidateLatitude, " +
                "candidateLongitude, startMs, endMs, dayEpoch, centroidLatitude, " +
                "centroidLongitude, radiusMeters, sampleCount, reliability, confirmed, " +
                "confidence, isOngoing, candidateCoordinateFrame, candidateOrigin " +
                "FROM visits WHERE id = ?",
            arrayOf(VISIT.id),
        ).use { cursor ->
            assertTrue("missing visit ${VISIT.id}", cursor.moveToFirst())
            assertEquals(VISIT.id, cursor.getLong(0))
            assertEquals(VISIT.placeId, cursor.getLong(1))

            // v2 candidates had no recorded coordinate frame, so the safe migration drops the
            // complete snapshot instead of guessing whether it can be promoted.
            (2..5).forEach { index ->
                assertTrue("candidate column $index was not cleared", cursor.isNull(index))
            }

            assertEquals(VISIT.startMs, cursor.getLong(6))
            assertEquals(VISIT.endMs, cursor.getLong(7))
            assertEquals(VISIT.dayEpoch, cursor.getLong(8))
            assertDoubleUnchanged(
                "visit centroid latitude",
                VISIT.centroidLatitude,
                cursor.getDouble(9),
            )
            assertDoubleUnchanged(
                "visit centroid longitude",
                VISIT.centroidLongitude,
                cursor.getDouble(10),
            )
            assertDoubleUnchanged("visit radius", VISIT.radiusMeters, cursor.getDouble(11))
            assertEquals(VISIT.sampleCount, cursor.getInt(12))
            assertDoubleUnchanged("visit reliability", VISIT.reliability, cursor.getDouble(13))
            assertEquals(VISIT.confirmed, cursor.getInt(14))
            assertDoubleUnchanged("visit confidence", VISIT.confidence, cursor.getDouble(15))
            assertEquals(VISIT.isOngoing, cursor.getInt(16))
            assertEquals("UNKNOWN", cursor.getString(17))
            assertEquals("UNKNOWN", cursor.getString(18))
            assertFalse("unexpected duplicate visit", cursor.moveToNext())
        }
    }

    private fun assertRepairJournal(database: SupportSQLiteDatabase) {
        database.query(
            "SELECT placeId, originalCoordinateState, repairedCoordinateState, decision, " +
                    "originalSource, repairedSource, originalLatitude, repairedLatitude, " +
                    "originalLongitude, repairedLongitude " +
                    "FROM place_coordinate_repairs ORDER BY placeId",
        ).use { cursor ->
            assertTrue(cursor.moveToNext())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("UNKNOWN", cursor.getString(1))
            assertEquals("LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE", cursor.getString(2))
            assertEquals("AUTO_CLASSIFIED_GOOGLE_PROVIDER_BASELINE", cursor.getString(3))
            assertEquals("MAPS", cursor.getString(4))
            assertEquals("MAPS", cursor.getString(5))
            assertDoubleUnchanged("journal place 1 latitude", cursor.getDouble(6), cursor.getDouble(7))
            assertDoubleUnchanged("journal place 1 longitude", cursor.getDouble(8), cursor.getDouble(9))

            assertTrue(cursor.moveToNext())
            assertEquals(4L, cursor.getLong(0))
            assertEquals("UNKNOWN", cursor.getString(1))
            assertEquals("WGS84_CANONICAL", cursor.getString(2))
            assertEquals("AUTO_OUTSIDE_MAINLAND_IDENTITY", cursor.getString(3))
            assertEquals("USER", cursor.getString(4))
            assertEquals("USER", cursor.getString(5))
            assertDoubleUnchanged("journal place 4 latitude", cursor.getDouble(6), cursor.getDouble(7))
            assertDoubleUnchanged("journal place 4 longitude", cursor.getDouble(8), cursor.getDouble(9))
            assertFalse("unexpected extra repair journal row", cursor.moveToNext())
        }
    }

    private fun SupportSQLiteDatabase.insertPlace(place: V2Place) {
        execSQL(
            "INSERT INTO places (id, name, latitude, longitude, radiusMeters, category, types, " +
                "source, googlePlaceId, address, confirmed, createdAtMs, fixed, anchorLatitude, " +
                "anchorLongitude, anchorRadiusMeters) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                place.id,
                "place-${place.id}",
                place.latitude,
                place.longitude,
                place.radiusMeters,
                "test-category",
                "test-category,point_of_interest",
                place.source,
                "google-place-${place.id}",
                "test address ${place.id}",
                1,
                1_719_840_000_000L + place.id,
                0,
                place.anchorLatitude,
                place.anchorLongitude,
                place.anchorRadiusMeters,
            ),
        )
    }

    private fun SupportSQLiteDatabase.insertVisit(visit: V2Visit) {
        execSQL(
            "INSERT INTO visits (id, placeId, candidateName, candidateGooglePlaceId, " +
                "candidateLatitude, candidateLongitude, startMs, endMs, dayEpoch, " +
                "centroidLatitude, centroidLongitude, radiusMeters, sampleCount, reliability, " +
                "confirmed, confidence, isOngoing) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                visit.id,
                visit.placeId,
                "Legacy candidate",
                "legacy-google-place",
                31.230_401_234_567_89,
                121.473_711_234_567_89,
                visit.startMs,
                visit.endMs,
                visit.dayEpoch,
                visit.centroidLatitude,
                visit.centroidLongitude,
                visit.radiusMeters,
                visit.sampleCount,
                visit.reliability,
                visit.confirmed,
                visit.confidence,
                visit.isOngoing,
            ),
        )
    }

    private fun assertDoubleUnchanged(label: String, expected: Double, actual: Double) {
        assertEquals(label, expected.toRawBits(), actual.toRawBits())
    }

    private data class V2Place(
        val id: Long,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double,
        val source: String,
        val anchorLatitude: Double,
        val anchorLongitude: Double,
        val anchorRadiusMeters: Double,
        val coordinateState: String,
    )

    private data class V2Visit(
        val id: Long,
        val placeId: Long,
        val startMs: Long,
        val endMs: Long,
        val dayEpoch: Long,
        val centroidLatitude: Double,
        val centroidLongitude: Double,
        val radiusMeters: Double,
        val sampleCount: Int,
        val reliability: Double,
        val confirmed: Int,
        val confidence: Double,
        val isOngoing: Int,
    )

    private companion object {
        const val TEST_DATABASE = "migration-2-3-test"

        val PLACES = listOf(
            // The sole classified legacy shape: an untouched MAPS center with a complete,
            // bit-identical baseline.
            V2Place(
                id = 1,
                latitude = 31.230_416_234_567_89,
                longitude = 121.473_701_234_567_89,
                radiusMeters = 47.125,
                source = "MAPS",
                anchorLatitude = 31.230_416_234_567_89,
                anchorLongitude = 121.473_701_234_567_89,
                anchorRadiusMeters = 31.875,
                coordinateState = "LEGACY_GOOGLE_MAP_CENTER_AND_BASELINE",
            ),
            // A MAPS row whose center no longer matches its baseline remains ambiguous.
            V2Place(
                id = 2,
                latitude = 39.904_211_234_567_89,
                longitude = 116.407_395_234_567_89,
                radiusMeters = 82.625,
                source = "MAPS",
                anchorLatitude = 39.904_201_234_567_89,
                anchorLongitude = 116.407_385_234_567_89,
                anchorRadiusMeters = 54.375,
                coordinateState = "UNKNOWN",
            ),
            // Equality alone is not enough: a non-Google row is not provider-frame legacy data.
            V2Place(
                id = 3,
                latitude = 22.543_096_234_567_89,
                longitude = 114.057_865_234_567_89,
                radiusMeters = 36.75,
                source = "USER",
                anchorLatitude = 22.543_096_234_567_89,
                anchorLongitude = 114.057_865_234_567_89,
                anchorRadiusMeters = 29.5,
                coordinateState = "UNKNOWN",
            ),
            // Safely beyond the mask's expanded global bounds: provider and WGS interpretations
            // are identical, so the migration may enable it without changing any numeric field.
            V2Place(
                id = 4,
                latitude = 1.352_083_234_567_89,
                longitude = 103.819_836_234_567_89,
                radiusMeters = 63.5,
                source = "USER",
                anchorLatitude = 1.352_073_234_567_89,
                anchorLongitude = 103.819_826_234_567_89,
                anchorRadiusMeters = 52.25,
                coordinateState = "WGS84_CANONICAL",
            ),
        )

        val VISIT = V2Visit(
            id = 101,
            placeId = 1,
            startMs = 1_719_840_123_456L,
            endMs = 1_719_840_987_654L,
            dayEpoch = 19_906L,
            centroidLatitude = 31.230_421_234_567_89,
            centroidLongitude = 121.473_691_234_567_89,
            radiusMeters = 24.625,
            sampleCount = 17,
            reliability = 0.8125,
            confirmed = 0,
            confidence = 0.6875,
            isOngoing = 1,
        )
    }
}
