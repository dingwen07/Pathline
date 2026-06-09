package net.extrawdw.apps.locationhistory.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        val map = linkedMapOf("home" to "1 Main St", "vibe" to "quiet", "stars" to "4")
        assertEquals(map.toList(), MemoryMap.decode(MemoryMap.encode(map)).toList())
    }

    @Test
    fun memory_decodeIsLenient() {
        assertTrue(MemoryMap.decode(null).isEmpty())
        assertTrue(MemoryMap.decode("").isEmpty())
        assertTrue(MemoryMap.decode("not json").isEmpty())
        // Non-string values are dropped rather than throwing on read.
        assertEquals(mapOf("a" to "x"), MemoryMap.decode("""{"a":"x","b":3,"c":{"n":1}}"""))
    }

    @Test
    fun memory_parseStrictAcceptsFlatStringMap() {
        assertEquals(mapOf("a" to "x", "b" to "y"), MemoryMap.parseStrict("""{"a":"x","b":"y"}"""))
        assertTrue(MemoryMap.parseStrict("{}").isEmpty())
    }

    @Test
    fun memory_parseStrictRejectsNonStringValues() {
        for (bad in listOf(
            """{"a":1}""",        // number
            """{"a":true}""",      // boolean
            """{"a":null}""",      // null
            """{"a":{"b":"c"}}""", // nested object
            """{"a":["x"]}""",     // array
            """["x"]""",           // non-object root
            """"x"""",             // bare string root
            """garbage""",         // not JSON
        )) {
            assertThrows(IllegalArgumentException::class.java) { MemoryMap.parseStrict(bad) }
        }
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
}
