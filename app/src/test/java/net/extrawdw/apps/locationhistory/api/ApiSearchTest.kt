package net.extrawdw.apps.locationhistory.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure-logic tests for the data API's search sanitization (FTS5 MATCH / LIKE / fields parsing). */
class ApiSearchTest {

    // --- ftsQuery --------------------------------------------------------------------------------

    @Test
    fun fts_singleColumnPrefixPhrase() {
        assertEquals("name : \"coffee\"*", ApiSearch.ftsQuery("coffee", listOf("name")))
    }

    @Test
    fun fts_multiColumnBracesAndTrim() {
        assertEquals(
            "{name address} : \"coffee sh\"*",
            ApiSearch.ftsQuery("  coffee sh  ", listOf("name", "address")),
        )
    }

    @Test
    fun fts_doubleQuotesAreEscapedNotInterpreted() {
        // FTS5 string escaping: a quote inside a phrase is doubled. Caller text can't break out.
        assertEquals(
            "name : \"he said \"\"hi\"\"\"*",
            ApiSearch.ftsQuery("he said \"hi\"", listOf("name")),
        )
    }

    @Test
    fun fts_matchSyntaxCharactersStayLiteralInsidePhrase() {
        // Operators like NOT/OR/-/^ are plain words/characters inside the quoted phrase.
        assertEquals(
            "name : \"a NOT b OR c*\"*",
            ApiSearch.ftsQuery("a NOT b OR c*", listOf("name")),
        )
    }

    @Test
    fun fts_requiresAColumn() {
        assertThrows(IllegalArgumentException::class.java) {
            ApiSearch.ftsQuery("x", emptyList())
        }
    }

    // --- likePattern -----------------------------------------------------------------------------

    @Test
    fun like_wrapsAndTrims() {
        assertEquals("%coffee%", ApiSearch.likePattern(" coffee "))
    }

    @Test
    fun like_escapesWildcardsAndEscapeChar() {
        assertEquals("%100\\%%", ApiSearch.likePattern("100%"))
        assertEquals("%a\\_b%", ApiSearch.likePattern("a_b"))
        assertEquals("%a\\\\b%", ApiSearch.likePattern("a\\b"))
    }

    // --- parseFields -----------------------------------------------------------------------------

    @Test
    fun fields_absentMeansNull() {
        assertNull(ApiSearch.parseFields(null))
    }

    @Test
    fun fields_normalizedAndDeduped() {
        assertEquals(
            listOf("name", "tags"),
            ApiSearch.parseFields(" Name , tags ,, NAME "),
        )
    }

    @Test
    fun fields_emptyStringMeansEmptyList() {
        assertEquals(emptyList<String>(), ApiSearch.parseFields(""))
    }
}
