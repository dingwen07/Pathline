package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiAccessMigrationsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath(TEST_DATABASE),
        driver = AndroidSQLiteDriver(),
        databaseClass = ApiAccessDatabase::class,
    )

    @Test
    fun migration1To2_preservesAuditEventsAndPlaceGrantLedger() = runBlocking {
        helper.createDatabase(1).use { connection ->
            connection.execute(
                "INSERT INTO api_access_events (id, packageName, dataType, startMs, endMs, " +
                        "rowCount, timestampMs, groupId, routeWithheld, deniedPermission) " +
                        "VALUES (1, 'example.caller', 'visits', 100, 200, 3, 300, NULL, 0, NULL)",
            )
            connection.execute(
                "INSERT INTO api_place_grants " +
                        "(packageName, placeId, firstGrantedMs, lastGrantedMs) " +
                        "VALUES ('example.caller', 42, 300, 400)",
            )
        }

        helper.runMigrationsAndValidate(
            version = 2,
            migrations = listOf(ApiAccessDatabase.MIGRATION_1_2),
        ).use { connection ->
            assertEquals(
                listOf("example.caller", "visits", "3", "0"),
                connection.singleRow(
                    "SELECT packageName, dataType, rowCount, isWrite " +
                            "FROM api_access_events WHERE id = 1",
                ),
            )
            assertEquals(
                listOf("example.caller", "42", "300", "400"),
                connection.singleRow(
                    "SELECT packageName, placeId, firstGrantedMs, lastGrantedMs " +
                            "FROM api_place_grants",
                ),
            )
        }
    }

    private fun SQLiteConnection.execute(sql: String) {
        prepare(sql).use { statement -> statement.step() }
    }

    private fun SQLiteConnection.singleRow(sql: String): List<String> =
        prepare(sql).use { statement ->
            check(statement.step()) { "query returned no rows: $sql" }
            List(statement.getColumnCount()) { index -> statement.getText(index) }
        }

    private companion object {
        const val TEST_DATABASE = "api-access-migration-test"
    }
}
