package net.extrawdw.apps.locationhistory.api

/**
 * Pure helpers for the data API's search mode ([PathlineContract.QueryParams.Q]): turning
 * caller-supplied query text into safe FTS5 MATCH expressions and LIKE patterns, and parsing the
 * `fields` parameter. Free of Android types so the sanitization rules are JVM unit-testable.
 *
 * ## Multi-word semantics
 * A query is split on whitespace/commas and a row matches when it contains **any** of the words
 * (each as a prefix) — OR semantics, which is what both humans and agents mean by several
 * keywords. Wrapping the whole query in double quotes requires the exact **phrase** instead
 * (`"south lions"`). A single word behaves identically under both readings.
 */
internal object ApiSearch {

    /** Word separators for multi-keyword queries. */
    private val TOKEN_SPLIT = Regex("[\\s,]+")

    /** Hard cap on OR'd terms — bounds the MATCH expression however long the caller's text is. */
    const val MAX_TOKENS = 8

    /**
     * Connector words dropped from multi-word queries ("bakery and cafe" means bakery|cafe, not
     * and|...): as bare any-of terms they only add noise — `"and"*` prefix-matches Andover, and a
     * `%the%` substring matches half of every note. Only dropped when meaningful words remain; a
     * query consisting solely of stopwords is searched literally.
     */
    private val STOPWORDS = setOf(
        "and", "or", "not", "the", "a", "an", "of", "in", "on", "at", "to", "for",
        "with", "near", "my", "me",
    )

    /** The query's keywords: split on whitespace/commas, connector stopwords dropped (unless
     *  nothing else remains), capped at [MAX_TOKENS]. */
    fun tokens(raw: String): List<String> {
        val all = raw.trim().split(TOKEN_SPLIT).mapNotNull { it.trim().ifEmpty { null } }
        val meaningful = all.filter { it.lowercase() !in STOPWORDS }
        return meaningful.ifEmpty { all }.take(MAX_TOKENS)
    }

    /** True when the caller wrapped the whole query in double quotes — exact-phrase intent. */
    fun isQuotedPhrase(raw: String): Boolean {
        val t = raw.trim()
        return t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")
    }

    /** The inner text of a [isQuotedPhrase] query. */
    private fun unquote(raw: String): String = raw.trim().removeSurrounding("\"")

    /**
     * The MATCH expression for [raw] against [columns] of an FTS5 table:
     *
     * - one word -> `{name address} : "coffee"*` (prefix phrase),
     * - several words -> `{name address} : ("coffee"* OR "star"*)` (any-of),
     * - quoted -> `{name address} : "south lions"*` (exact phrase, prefix on the last token).
     *
     * Every term is a quoted FTS5 string with internal double quotes doubled, so caller text can
     * never inject MATCH syntax. [columns] are our own identifiers, never caller input.
     */
    fun ftsQuery(raw: String, columns: List<String>): String {
        require(columns.isNotEmpty()) { "at least one FTS column required" }
        val cols =
            if (columns.size == 1) columns.single()
            else columns.joinToString(" ", prefix = "{", postfix = "}")
        if (isQuotedPhrase(raw)) return "$cols : ${phrase(unquote(raw))}"
        val toks = tokens(raw)
        return when (toks.size) {
            0 -> "$cols : ${phrase(raw.trim())}"
            1 -> "$cols : ${phrase(toks.single())}"
            else -> "$cols : (${toks.joinToString(" OR ") { phrase(it) }})"
        }
    }

    /** One quoted prefix-phrase term, FTS5-escaped (internal `"` doubled). */
    private fun phrase(text: String): String = "\"" + text.replace("\"", "\"\"") + "\"*"

    /** `%text%` with the LIKE wildcards (`%`, `_`) and the escape character itself escaped —
     *  pair with `ESCAPE '\'` for a literal, case-insensitive substring match. */
    fun likePattern(raw: String): String {
        val escaped = raw.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    /**
     * The LIKE patterns for [raw] under the same multi-word semantics as [ftsQuery]: one pattern
     * per keyword (caller unions the matches — any-of), or a single pattern for a quoted phrase.
     */
    fun likePatterns(raw: String): List<String> {
        if (isQuotedPhrase(raw)) return listOf(likePattern(unquote(raw)))
        val toks = tokens(raw)
        return if (toks.isEmpty()) listOf(likePattern(raw)) else toks.map { likePattern(it) }
    }

    /**
     * The substrings to test in code-side matching (memory keys/values) — keywords for a plain
     * query, the inner text for a quoted phrase. Mirrors [ftsQuery]/[likePatterns] semantics.
     */
    fun needles(raw: String): List<String> =
        if (isQuotedPhrase(raw)) listOf(unquote(raw)) else tokens(raw).ifEmpty { listOf(raw.trim()) }

    /**
     * The `fields` parameter as a normalized list (trimmed, lowercased, de-duplicated, empties
     * dropped), or null when the parameter was absent — "match everything the caller may".
     */
    fun parseFields(raw: String?): List<String>? =
        raw?.split(',')?.mapNotNull { it.trim().lowercase().ifEmpty { null } }?.distinct()
}
