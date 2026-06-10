package net.extrawdw.apps.locationhistory.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the Phase 2 annotation domain (canonicalization, memory codec, merge fold). */
class AnnotationDomainTest {

    // --- TagCanonicalizer ----------------------------------------------------------------------

    @Test
    fun canonical_collapsesCaseAndSeparatorsToHyphen() {
        val canonical = "my-home"
        for (spelling in listOf("My Home", "my  home", "my-home", "my_home", "My HoME", " my - home ", "my _- home")) {
            assertEquals(spelling, canonical, TagCanonicalizer.canonicalize(spelling))
        }
    }

    @Test
    fun canonical_normalizesSeparatorsRatherThanStripping() {
        // Separators become a single hyphen (word boundaries survive); they are NOT removed.
        assertEquals("coffee-shop", TagCanonicalizer.canonicalize("Coffee Shop"))
        assertEquals("coffee-shop", TagCanonicalizer.canonicalize("COFFEE_SHOP"))
        // No-separator spelling is therefore a DISTINCT tag from the separated one.
        assertEquals("coffeeshop", TagCanonicalizer.canonicalize("CoffeeShop"))
        // Stripping would have collided these; normalizing keeps them apart.
        assertEquals("ab-cd", TagCanonicalizer.canonicalize("ab cd"))
        assertEquals("abc-d", TagCanonicalizer.canonicalize("abc d"))
    }

    @Test
    fun canonical_separatorOnlyIsEmpty() {
        assertEquals("", TagCanonicalizer.canonicalize("  - _ "))
        assertEquals("", TagCanonicalizer.canonicalize(""))
    }

    // --- MemoryMap codec -----------------------------------------------------------------------

    @Test
    fun memory_roundTripsAndPreservesOrder() {
        val map = linkedMapOf(
            "home" to MemoryEntry("1 Main St"),
            "vibe" to MemoryEntry("quiet", 0.6f),
            "stars" to MemoryEntry("4", 0.85f, "user statement"),
        )
        assertEquals(map.toList(), MemoryMap.decode(MemoryMap.encode(map)).toList())
    }

    @Test
    fun memory_sourceIsOptionalAndNormalized() {
        // Absent source decodes as null (incl. the pre-source stored format).
        assertEquals(
            mapOf("a" to MemoryEntry("x", 0.5f, null)),
            MemoryMap.decode("""{"a":{"value":"x","confidence":0.5}}"""),
        )
        // A blank stored source reads as none.
        assertEquals(
            mapOf("a" to MemoryEntry("x", 1f, null)),
            MemoryMap.decode("""{"a":{"value":"x","source":"  "}}"""),
        )
        // A null source is omitted from the encoded form entirely.
        assertEquals("""{"a":{"value":"x","confidence":1.0}}""", MemoryMap.encode(mapOf("a" to MemoryEntry("x"))))
    }

    @Test
    fun memory_confidenceDefaultsToCertain() {
        assertEquals(1f, MemoryEntry("x").confidence, 0f)
        // A bare-string value (the pre-confidence dev format) reads as stated-as-fact.
        assertEquals(
            mapOf("a" to MemoryEntry("x", 1f)),
            MemoryMap.decode("""{"a":"x"}"""),
        )
        // An entry object without a confidence likewise.
        assertEquals(
            mapOf("a" to MemoryEntry("x", 1f)),
            MemoryMap.decode("""{"a":{"value":"x"}}"""),
        )
    }

    @Test
    fun memory_decodeIsLenient() {
        assertTrue(MemoryMap.decode(null).isEmpty())
        assertTrue(MemoryMap.decode("").isEmpty())
        assertTrue(MemoryMap.decode("not json").isEmpty())
        // Malformed entries are dropped rather than throwing on read; out-of-range confidence clamps.
        assertEquals(
            mapOf("a" to MemoryEntry("x"), "d" to MemoryEntry("y", 1f), "e" to MemoryEntry("z", 0f)),
            MemoryMap.decode(
                """{"a":"x","b":3,"c":{"n":1},"d":{"value":"y","confidence":7},"e":{"value":"z","confidence":-1}}""",
            ),
        )
    }

    // --- foldText (note + memory-conflict merge rule) ------------------------------------------

    @Test
    fun foldText_keepsTheNonBlankSide() {
        assertNull(foldText(null, null))
        assertNull(foldText("  ", ""))
        assertEquals("solo", foldText("  solo  ", null))
        assertEquals("solo", foldText(null, "  solo  "))
    }

    @Test
    fun foldText_collapsesEqualAndConcatsDiffering() {
        assertEquals("same", foldText("same", " same "))
        assertEquals("older\n\nnewer", foldText("  older ", " newer "))
    }

    // --- foldSources (memory-merge provenance rule) ---------------------------------------------

    @Test
    fun foldSources_skipsBlanksAndCollapsesEqual() {
        assertNull(foldSources(null, null))
        assertNull(foldSources(" ", ""))
        assertEquals("user statement", foldSources("user statement", null))
        assertEquals("user statement", foldSources(" user statement ", "user statement"))
    }

    @Test
    fun foldSources_joinsDifferingOldestFirst() {
        assertEquals(
            "visit:75 note; user statement",
            foldSources("visit:75 note", " user statement "),
        )
    }
}
