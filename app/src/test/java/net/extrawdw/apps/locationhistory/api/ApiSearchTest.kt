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
    fun fts_multiWordBecomesAnyOfPrefixTerms() {
        // Several keywords = OR of prefix phrases (any-of), not a single exact phrase.
        assertEquals(
            "{name address} : (\"coffee\"* OR \"sh\"*)",
            ApiSearch.ftsQuery("  coffee sh  ", listOf("name", "address")),
        )
    }

    @Test
    fun fts_commasSeparateKeywordsToo() {
        assertEquals(
            "name : (\"bakery\"* OR \"cafe\"* OR \"dessert\"*)",
            ApiSearch.ftsQuery("bakery, cafe,dessert", listOf("name")),
        )
    }

    @Test
    fun fts_connectorStopwordsAreDropped() {
        // "and"/"the"/... as bare any-of terms only add noise ("and"* matches Andover).
        assertEquals(
            "name : (\"bakery\"* OR \"cafe\"*)",
            ApiSearch.ftsQuery("the bakery and cafe", listOf("name")),
        )
        // Dropping a stopword can leave a single keyword — plain prefix term, no parentheses.
        assertEquals("name : \"bakery\"*", ApiSearch.ftsQuery("the bakery", listOf("name")))
    }

    @Test
    fun fts_allStopwordQueryIsSearchedLiterally() {
        assertEquals("name : \"the\"*", ApiSearch.ftsQuery("the", listOf("name")))
        assertEquals(
            "name : (\"of\"* OR \"the\"*)",
            ApiSearch.ftsQuery("of the", listOf("name")),
        )
    }

    @Test
    fun fts_quotedQueryStaysAnExactPhrase() {
        assertEquals(
            "{name address} : \"south lions\"*",
            ApiSearch.ftsQuery(" \"south lions\" ", listOf("name", "address")),
        )
    }

    @Test
    fun fts_doubleQuotesAreEscapedNotInterpreted() {
        // FTS5 string escaping: a quote inside a term is doubled. Caller text can't break out.
        // ("he", "said", "\"hi\"" are three tokens; the third keeps its quotes, doubled.)
        assertEquals(
            "name : (\"he\"* OR \"said\"* OR \"\"\"hi\"\"\"*)",
            ApiSearch.ftsQuery("he said \"hi\"", listOf("name")),
        )
    }

    @Test
    fun fts_matchSyntaxCharactersStayLiteralInsideTerms() {
        // FTS operators can't be injected: NOT/OR are connector stopwords (dropped), and special
        // characters stay inside their quoted term.
        assertEquals(
            "name : (\"deli\"* OR \"park^2\"* OR \"cafe*\"*)",
            ApiSearch.ftsQuery("deli NOT park^2 OR cafe*", listOf("name")),
        )
    }

    @Test
    fun fts_tokenCountIsCapped() {
        val q = ApiSearch.ftsQuery((1..20).joinToString(" ") { "w$it" }, listOf("name"))
        assertEquals(ApiSearch.MAX_TOKENS, Regex("OR").findAll(q).count() + 1)
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

    @Test
    fun likePatterns_onePerKeyword() {
        assertEquals(listOf("%bakery%", "%cafe%"), ApiSearch.likePatterns("bakery cafe"))
        assertEquals(listOf("%coffee%"), ApiSearch.likePatterns(" coffee "))
        // Connector stopwords don't become substring patterns either.
        assertEquals(listOf("%bakery%", "%cafe%"), ApiSearch.likePatterns("bakery and cafe"))
    }

    @Test
    fun likePatterns_quotedQueryStaysOneSubstring() {
        assertEquals(listOf("%south lions%"), ApiSearch.likePatterns("\"south lions\""))
    }

    // --- tokens / needles ------------------------------------------------------------------------

    @Test
    fun tokens_splitOnWhitespaceAndCommas() {
        assertEquals(listOf("gym", "pool", "sauna"), ApiSearch.tokens(" gym  pool,sauna "))
    }

    @Test
    fun needles_keywordsOrQuotedPhrase() {
        assertEquals(listOf("dim", "sum"), ApiSearch.needles("dim sum"))
        assertEquals(listOf("south lions"), ApiSearch.needles("\"south lions\""))
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
