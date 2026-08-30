package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FtsMigrationsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath(TEST_DATABASE),
        driver = SQLCipherDriver(TEST_PASSPHRASE.copyOf(), null, null),
        databaseClass = AppDatabase::class,
    )

    @Test
    fun migration1To4_preservesSearchData_replacesLegacyTriggers_andKeepsIndexesInSync() =
        runBlocking {
            helper.createDatabase(1).use { connection ->
                connection.execute(
                    "INSERT INTO places (id, name, latitude, longitude, radiusMeters, category, " +
                            "source, googlePlaceId, address, confirmed, createdAtMs, fixed, " +
                            "anchorLatitude, anchorLongitude, anchorRadiusMeters) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    1L,
                    "Harbour Coffee",
                    1.3521,
                    103.8198,
                    50.0,
                    "cafe",
                    "USER",
                    null,
                    "One Marina Way",
                    1,
                    1_719_840_000_000L,
                    0,
                    1.3521,
                    103.8198,
                    50.0,
                )
            }

            helper.runMigrationsAndValidate(
                version = 3,
                migrations = listOf(
                    AppMigrations.MIGRATION_1_2,
                    AppMigrations.MIGRATION_2_3,
                ),
            ).use { connection ->
                connection.execute(
                    "INSERT INTO tags (id, canonicalName, displayName, createdAtMs, createdBy) " +
                            "VALUES (?, ?, ?, ?, ?)",
                    2L,
                    "favourite",
                    "Favourite",
                    1_719_840_000_001L,
                    null,
                )
                connection.execute(
                    "INSERT INTO concepts (id, canonicalName, displayName, kind, description, " +
                            "createdAtMs, updatedAtMs, createdBy, updatedBy, archivedAtMs, archivedBy) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    3L,
                    "weekend-cafes",
                    "Weekend Cafes",
                    "collection",
                    "Coffee places to revisit",
                    1_719_840_000_002L,
                    1_719_840_000_002L,
                    null,
                    null,
                    null,
                    null,
                )

                assertEquals(listOf(1L), connection.matchIds("places_fts", "coffee"))
                assertEquals(listOf(2L), connection.matchIds("tags_fts", "favourite"))
                assertEquals(listOf(3L), connection.matchIds("concepts_fts", "revisit"))
                assertEquals(9, connection.triggerNames("trg_%_fts_%").size)
            }

            helper.runMigrationsAndValidate(
                version = 4,
                migrations = listOf(AppMigrations.MIGRATION_3_4),
            ).use { connection ->
                assertEquals(listOf(1L), connection.matchIds("places_fts", "coffee"))
                assertEquals(listOf(2L), connection.matchIds("tags_fts", "favourite"))
                assertEquals(listOf(3L), connection.matchIds("concepts_fts", "revisit"))

                assertTrue(connection.triggerNames("trg_%_fts_%").isEmpty())
                assertEquals(12, connection.triggerNames("room_fts_content_sync_%").size)

                connection.execute("UPDATE places SET name = ? WHERE id = ?", "Harbour Bakery", 1L)
                assertTrue(connection.matchIds("places_fts", "coffee").isEmpty())
                assertEquals(listOf(1L), connection.matchIds("places_fts", "bakery"))

                connection.execute("DELETE FROM tags WHERE id = ?", 2L)
                assertTrue(connection.matchIds("tags_fts", "favourite").isEmpty())

                connection.execute(
                    "INSERT INTO concepts (id, canonicalName, displayName, kind, description, " +
                            "createdAtMs, updatedAtMs, createdBy, updatedBy, archivedAtMs, archivedBy) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    4L,
                    "museum-wishlist",
                    "Museum Wishlist",
                    "collection",
                    "Exhibitions for the next holiday",
                    1_719_840_000_003L,
                    1_719_840_000_003L,
                    null,
                    null,
                    null,
                    null,
                )
                assertEquals(listOf(4L), connection.matchIds("concepts_fts", "exhibitions"))

                assertEquals("ok", connection.scalarText("PRAGMA integrity_check"))
                assertFalse(connection.scalarText("PRAGMA cipher_version").isBlank())
            }
        }

    @Test
    fun freshVersion4_createsRoomManagedFtsIndexesAndTriggers() = runBlocking {
        helper.createDatabase(4).use { connection ->
            connection.execute(
                "INSERT INTO tags (id, canonicalName, displayName, createdAtMs, createdBy) " +
                        "VALUES (?, ?, ?, ?, ?)",
                10L,
                "room-managed",
                "Room Managed",
                1_719_840_000_010L,
                null,
            )

            assertEquals(listOf(10L), connection.matchIds("tags_fts", "managed"))
            assertTrue(connection.triggerNames("trg_%_fts_%").isEmpty())
            assertEquals(12, connection.triggerNames("room_fts_content_sync_%").size)
        }
    }

    private fun SQLiteConnection.execute(sql: String, vararg values: Any?) {
        prepare(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.bindValue(index + 1, value) }
            statement.step()
        }
    }

    private fun SQLiteConnection.matchIds(tableName: String, match: String): List<Long> {
        require(tableName in FTS_TABLE_NAMES)
        return prepare(
            "SELECT rowid FROM $tableName WHERE $tableName MATCH ? ORDER BY rowid",
        ).use { statement ->
            statement.bindText(1, match)
            buildList {
                while (statement.step()) add(statement.getLong(0))
            }
        }
    }

    private fun SQLiteConnection.triggerNames(pattern: String): List<String> =
        prepare(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE ? ORDER BY name",
        ).use { statement ->
            statement.bindText(1, pattern)
            buildList {
                while (statement.step()) add(statement.getText(0))
            }
        }

    private fun SQLiteConnection.scalarText(sql: String): String =
        prepare(sql).use { statement ->
            check(statement.step()) { "query returned no rows: $sql" }
            statement.getText(0)
        }

    private fun SQLiteStatement.bindValue(index: Int, value: Any?) {
        when (value) {
            null -> bindNull(index)
            is String -> bindText(index, value)
            is Long -> bindLong(index, value)
            is Int -> bindLong(index, value.toLong())
            is Double -> bindDouble(index, value)
            is Float -> bindDouble(index, value.toDouble())
            is Boolean -> bindLong(index, if (value) 1 else 0)
            is ByteArray -> bindBlob(index, value)
            else -> error("unsupported SQLite bind type: ${value::class}")
        }
    }

    private companion object {
        const val TEST_DATABASE = "fts-migration-test"
        val TEST_PASSPHRASE: ByteArray =
            "x'202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f'"
                .toByteArray(Charsets.US_ASCII)
        val FTS_TABLE_NAMES = setOf("places_fts", "tags_fts", "concepts_fts")

        init {
            System.loadLibrary("sqlcipher")
        }
    }
}
