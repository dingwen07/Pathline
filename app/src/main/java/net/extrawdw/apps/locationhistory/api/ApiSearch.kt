package net.extrawdw.apps.locationhistory.api

/**
 * Pure helpers for the data API's search mode ([PathlineContract.QueryParams.Q]): turning
 * caller-supplied query text into safe FTS5 MATCH expressions and LIKE patterns, and parsing the
 * `fields` parameter. Free of Android types so the sanitization rules are JVM unit-testable.
 */
internal object ApiSearch {

    /**
     * The MATCH expression for [raw] against [columns] of an FTS5 table, e.g.
     * `{name address} : "coffee sh"*` — the whole text is one quoted phrase with a trailing `*`
     * (prefix match on its last token), restricted to the given columns. Internal double quotes are
     * doubled per FTS5 string escaping, so caller text can never inject MATCH syntax. [columns] are
     * our own identifiers, never caller input.
     */
    fun ftsQuery(raw: String, columns: List<String>): String {
        require(columns.isNotEmpty()) { "at least one FTS column required" }
        val phrase = "\"" + raw.trim().replace("\"", "\"\"") + "\"*"
        val cols =
            if (columns.size == 1) columns.single()
            else columns.joinToString(" ", prefix = "{", postfix = "}")
        return "$cols : $phrase"
    }

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
     * The `fields` parameter as a normalized list (trimmed, lowercased, de-duplicated, empties
     * dropped), or null when the parameter was absent — "match everything the caller may".
     */
    fun parseFields(raw: String?): List<String>? =
        raw?.split(',')?.mapNotNull { it.trim().lowercase().ifEmpty { null } }?.distinct()
}
