package net.extrawdw.apps.locationhistory.data.db

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** One matched rowid from an FTS5 virtual table (see [SearchDao]). */
data class FtsRowId(val id: Long)

/**
 * Raw access to the hand-rolled FTS5 virtual tables (`places_fts`, `tags_fts` — see
 * [AppDatabase.FTS_CREATE]). They are not Room entities (Room 2.x has no `@Fts5`), so a compile-
 * checked `@Query` cannot reference them; the MATCH runs through `@RawQuery` instead. Callers build
 * the [SupportSQLiteQuery] with the match expression as a **bind argument** (never concatenated),
 * e.g. `SimpleSQLiteQuery("SELECT rowid AS id FROM places_fts WHERE places_fts MATCH ?", arrayOf(m))`.
 */
@Dao
interface SearchDao {

    @RawQuery
    suspend fun matchRowIds(query: SupportSQLiteQuery): List<FtsRowId>
}
