package net.extrawdw.apps.locationhistory.debug

import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class DatabaseQueryTest {
    @Test fun queryPreservesTypesAndSupportsBoundParameters() = withDatabase { file, passphrase ->
        val lines = mutableListOf<String>()
        DatabaseQuery.query(file, passphrase,
            "WITH readings AS (SELECT * FROM measurements WHERE time >= ?) SELECT * FROM readings ORDER BY time",
            arrayOf("20"), lines::add)
        assertEquals(listOf("time", "speed", "note", "data"),
            JSONObject(lines.first()).getJSONArray("columns").let { columns -> (0 until columns.length()).map(columns::getString) })
        val row = JSONArray(lines[1])
        assertEquals(20L, row.getLong(0))
        assertEquals(1.5, row.getDouble(1), 0.0)
        assertEquals(JSONObject.NULL, row.get(2))
        assertEquals("AQI=", row.getJSONObject(3).getString("base64"))
        assertFalse(JSONObject(lines.last()).getBoolean("truncated"))
    }

    @Test fun writesAndWrongKeysCannotModifyOrDeleteTheDatabase() = withDatabase { file, passphrase ->
        for (sql in listOf("DELETE FROM measurements", "UPDATE measurements SET speed = 9",
            "DROP TABLE measurements", "PRAGMA user_version = 9", "ATTACH ':memory:' AS other")) {
            assertThrows(Exception::class.java) { DatabaseQuery.query(file, passphrase, sql, output = {}) }
        }
        assertThrows(Exception::class.java) {
            DatabaseQuery.query(file, "wrong-key".toByteArray(), "SELECT * FROM measurements", output = {})
        }
        val lines = mutableListOf<String>()
        DatabaseQuery.query(file, passphrase, "SELECT COUNT(*), SUM(speed) FROM measurements", output = lines::add)
        val row = JSONArray(lines[1])
        assertEquals(2, row.getInt(0))
        assertEquals(1.5, row.getDouble(1), 0.0)
    }

    private fun withDatabase(block: (File, ByteArray) -> Unit) {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "debug-query-${System.nanoTime()}.db")
        val passphrase = "debug-query-test".toByteArray()
        try {
            SQLiteDatabase.openOrCreateDatabase(file.path, passphrase, null, null).use { database ->
                database.execSQL("CREATE TABLE measurements(time INTEGER, speed REAL, note TEXT, data BLOB)")
                database.execSQL("INSERT INTO measurements VALUES (10,0,'stop',NULL),(20,1.5,NULL,x'0102')")
            }
            block(file, passphrase)
        } finally {
            context.deleteDatabase(file.path)
        }
    }
}
