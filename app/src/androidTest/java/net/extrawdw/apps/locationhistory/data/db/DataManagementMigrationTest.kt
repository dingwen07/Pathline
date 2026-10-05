package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataManagementMigrationTest {
    init { System.loadLibrary("sqlcipher") }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath("data-management-migration-${java.util.UUID.randomUUID()}.db"),
        driver = SQLCipherDriver("migration-test".toByteArray(), null, null),
        databaseClass = AppDatabase::class,
    )

    @Test fun upgradeFrom4PreservesRowsAndDefaultsToNoDeletionProtection() = runBlocking {
        helper.createDatabase(4).use { db ->
            db.execSQL("INSERT INTO visits (id,placeId,candidateName,candidateGooglePlaceId,candidateLatitude,candidateLongitude,startMs,endMs,dayEpoch,centroidLatitude,centroidLongitude,radiusMeters,sampleCount,reliability,confirmed,confidence,isOngoing,candidateCoordinateFrame,candidateOrigin) VALUES (42,NULL,NULL,NULL,NULL,NULL,100,200,0,1.0,2.0,50,2,0.8,1,1.0,0,'UNKNOWN','UNKNOWN')")
            db.execSQL("INSERT INTO trips (id,fromVisitId,toVisitId,startMs,endMs,dayEpoch,mode,modeConfidence,encodedPolyline,distanceMeters,confirmed) VALUES (99,42,NULL,200,300,0,'WALKING',1.0,'',0.0,0)")
        }
        helper.runMigrationsAndValidate(5, listOf(AppMigrations.MIGRATION_4_5)).use { db ->
            db.prepare("SELECT id,startMs,endMs,stopMerge FROM visits").use { row ->
                assertTrue(row.step()); assertEquals(42, row.getLong(0)); assertEquals(100, row.getLong(1))
                assertEquals(200, row.getLong(2)); assertEquals(0, row.getLong(3)); assertFalse(row.step())
            }
            db.prepare("SELECT id,fromVisitId,stopMerge FROM trips").use { row ->
                assertTrue(row.step()); assertEquals(99, row.getLong(0)); assertEquals(42, row.getLong(1)); assertEquals(0, row.getLong(2))
            }
            db.prepare("SELECT COUNT(*) FROM deleted_time_ranges").use { row -> assertTrue(row.step()); assertEquals(0, row.getLong(0)) }
        }
    }

    @Test fun allPublishedMigrationsReachCurrentSchema() = runBlocking {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(AppDatabase.SCHEMA_VERSION, AppMigrations.ALL.toList()).close()
    }
}
