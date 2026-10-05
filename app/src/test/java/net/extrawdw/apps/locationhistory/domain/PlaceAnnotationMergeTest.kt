package net.extrawdw.apps.locationhistory.domain

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.ConceptEntity
import net.extrawdw.apps.locationhistory.data.db.ConceptMemberEntity
import org.junit.Assert.*
import org.junit.Test

class PlaceAnnotationMergeTest {
    private val tags = FakeTagDao()
    private val annotations = FakeAnnotationDao()
    private val concepts = FakeConceptDao()
    private val store = AnnotationStore(tags, annotations, concepts)
    private val target = AnnotationTarget.PLACE

    @Test
    fun appendTrimsOnlyJoiningEdgesAndDoesNotDeduplicate() {
        assertEquals("  kept\nremoved  ", appendPlaceText("  kept \n\t", " \nremoved  "))
        assertEquals("same\nsame", appendPlaceText("same", "same"))
        assertEquals("  kept  ", appendPlaceText("  kept  ", ""))
        assertEquals("  kept  ", appendPlaceText("  kept  ", null))
        assertEquals("\nremoved  ", appendPlaceText(null, "  removed  "))
        assertEquals("kept\n", appendPlaceText("kept ", " \t"))
    }

    @Test
    fun mergesNotesTagsAndConceptsAndClearsAllSourceAnnotations() = runBlocking {
        store.saveEdits(target, 1, " destination  ", listOf("shared", "kept"))
        store.saveEdits(target, 2, "  source ", listOf("shared", "added"))
        val concept = concepts.insertIgnore(ConceptEntity(canonicalName = "trip", displayName = "Trip",
            createdAtMs = 0, updatedAtMs = 0))
        concepts.addMember(ConceptMemberEntity(concept, target, 1, 0))
        concepts.addMember(ConceptMemberEntity(concept, target, 2, 1))
        merge()
        assertEquals(" destination\nsource ", store.getNote(target, 1))
        assertEquals(setOf("shared", "kept", "added"), store.tagsFor(target, 1).map { it.displayName }.toSet())
        assertEquals(listOf(1L), concepts.membersOf(concept).map { it.targetId })
        assertEquals(AnnotationData("", emptyList(), emptyMap()), store.loadEdits(target, 2))
        assertTrue(concepts.membershipsFor(target, 2).isEmpty())
    }

    @Test
    fun memoryCollisionsAppendEvenEqualValuesAndPreserveNoncollidingMetadata() = runBlocking {
        val untouched = MemoryEntry("kept", .9f, "original", 1, "app.one")
        val moved = MemoryEntry(" moved ", .8f, "source", 2, "app.two")
        store.setMemories(target, 1, mapOf("shared" to untouched, "kept" to untouched, "empty" to untouched))
        store.setMemories(target, 2, mapOf("shared" to untouched, "added" to moved, "empty" to MemoryEntry("")))
        merge()
        val result = store.getMemories(target, 1)
        assertEquals("kept\nkept", result.getValue("shared").value)
        assertNull(result.getValue("shared").updatedBy)
        assertEquals(untouched, result["kept"])
        assertEquals(untouched, result["empty"])
        assertEquals(moved, result["added"])
        assertTrue(store.getMemories(target, 2).isEmpty())
    }

    @Test
    fun memoryCollisionPreservesOuterWhitespaceAndCombinesProvenance() = runBlocking {
        store.setMemories(target, 1, mapOf("key" to MemoryEntry("  one \n", .8f, "a", 1, "app.one")))
        store.setMemories(target, 2, mapOf("key" to MemoryEntry(" \ttwo  ", .4f, "b", 2, "app.two")))
        merge()
        assertEquals(MemoryEntry("  one\ntwo  ", .4f, "a; b", 2, null), store.getMemories(target, 1)["key"])
    }

    @Test
    fun sourceWithoutNoteLeavesDestinationUntouched() = runBlocking {
        store.setNote(target, 1, "  destination  ", "app.one")
        merge()
        assertEquals("  destination  ", store.getNote(target, 1))
    }

    private suspend fun merge() = store.withMemoryWriteLock { store.foldPlaceOnMerge(1, 2) }
}
