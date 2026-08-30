package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.Dao
import androidx.room3.Query

/** One matched rowid from a Room-managed FTS5 virtual table. */
data class FtsRowId(val id: Long)

/** Compile-checked FTS5 searches. Every MATCH expression remains a bound argument. */
@Dao
interface SearchDao {

    @Query(
        "SELECT rowid AS id FROM places_fts " +
                "WHERE places_fts MATCH :match ORDER BY bm25(places_fts)",
    )
    suspend fun matchPlaces(match: String): List<FtsRowId>

    @Query(
        "SELECT rowid AS id FROM tags_fts " +
                "WHERE tags_fts MATCH :match ORDER BY bm25(tags_fts)",
    )
    suspend fun matchTags(match: String): List<FtsRowId>

    @Query(
        "SELECT rowid AS id FROM concepts_fts " +
                "WHERE concepts_fts MATCH :match ORDER BY bm25(concepts_fts)",
    )
    suspend fun matchConcepts(match: String): List<FtsRowId>
}
