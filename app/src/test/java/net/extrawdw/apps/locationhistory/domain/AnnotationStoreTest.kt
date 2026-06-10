package net.extrawdw.apps.locationhistory.domain

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the in-app editor's load/save path ([AnnotationStore.loadEdits] /
 * [AnnotationStore.saveEdits]) against in-memory fake DAOs — proving notes AND tags persist together,
 * tags carry the new hyphen canonical, clearing/removal works, and writer attribution follows the
 * null-means-Pathline convention. (DAO wiring itself is exercised by on-device runs; this pins the
 * domain contract the UI and provider rely on.)
 */
class AnnotationStoreTest {

    private val tagDao = FakeTagDao()
    private val annotationDao = FakeAnnotationDao()
    private val conceptDao = FakeConceptDao()
    private val store = AnnotationStore(tagDao, annotationDao, conceptDao)

    @Test
    fun saveEdits_persistsNoteAndTagsTogether() = runBlocking {
        store.saveEdits(AnnotationTarget.VISIT, 1, note = "Lunch with Sam", tags = listOf("Food", "My Home"))

        val loaded = store.loadEdits(AnnotationTarget.VISIT, 1)
        assertEquals("Lunch with Sam", loaded.note)
        assertEquals(listOf("Food", "My Home"), loaded.tags) // display spellings preserved, ordered by name
    }

    @Test
    fun saveEdits_tagUsesHyphenCanonical_soSeparatorSpellingsCollapse() = runBlocking {
        store.saveEdits(AnnotationTarget.TRIP, 7, note = "", tags = listOf("My Home"))
        // A different separator spelling of the same tag must reuse the one row, not create a second.
        store.saveEdits(AnnotationTarget.VISIT, 8, note = "", tags = listOf("my_home"))

        assertEquals("my-home", NameCanonicalizer.canonicalize("My Home"))
        assertEquals(1, store.tagsFor(AnnotationTarget.TRIP, 7).size)
        assertEquals(
            store.tagsFor(AnnotationTarget.TRIP, 7).single().id,
            store.tagsFor(AnnotationTarget.VISIT, 8).single().id,
        )
    }

    @Test
    fun saveEdits_blankNoteClearsExisting() = runBlocking {
        store.saveEdits(AnnotationTarget.PLACE, 2, note = "draft", tags = emptyList())
        assertEquals("draft", store.getNote(AnnotationTarget.PLACE, 2))

        store.saveEdits(AnnotationTarget.PLACE, 2, note = "   ", tags = emptyList())
        assertNull(store.getNote(AnnotationTarget.PLACE, 2))
    }

    @Test
    fun saveEdits_reconcilesTagsAgainstStored() = runBlocking {
        store.saveEdits(AnnotationTarget.VISIT, 3, note = "", tags = listOf("a", "b", "c"))
        // Drop "b", keep "a"/"c", add "d" — a stateless diff against what's stored.
        store.saveEdits(AnnotationTarget.VISIT, 3, note = "", tags = listOf("a", "c", "d"))

        assertEquals(listOf("a", "c", "d"), store.tagsFor(AnnotationTarget.VISIT, 3).map { it.displayName })
    }

    @Test
    fun memories_differingValuesFoldToConcatWithWeakerConfidence() = runBlocking {
        store.putMemory(AnnotationTarget.VISIT, 10, "k", "old", 0.9f)
        store.putMemory(AnnotationTarget.VISIT, 11, "k", "new", 0.4f)
        val newerStamp = maxOf(
            store.getMemories(AnnotationTarget.VISIT, 10).getValue("k").updatedAtMs!!,
            store.getMemories(AnnotationTarget.VISIT, 11).getValue("k").updatedAtMs!!,
        )

        store.foldOnMerge(AnnotationTarget.VISIT, survivorId = 10, dyingId = 11)

        val folded = store.getMemories(AnnotationTarget.VISIT, 10).getValue("k")
        assertEquals("old\n\nnew", folded.value)
        assertEquals(0.4f, folded.confidence, 0f) // differing values -> min confidence
        assertEquals(newerStamp, folded.updatedAtMs) // fold keeps the later write stamp
    }

    @Test
    fun memories_equalValuesFoldToOneWithStrongerConfidence() = runBlocking {
        store.putMemory(AnnotationTarget.VISIT, 20, "k", "same", 0.5f)
        store.putMemory(AnnotationTarget.VISIT, 21, "k", "same", 0.8f)

        store.foldOnMerge(AnnotationTarget.VISIT, survivorId = 20, dyingId = 21)

        val folded = store.getMemories(AnnotationTarget.VISIT, 20).getValue("k")
        assertEquals("same", folded.value)
        assertEquals(0.8f, folded.confidence, 0f) // equal values -> max confidence
    }

    @Test
    fun putMemory_defaultsToCertainAndClampsConfidence() = runBlocking {
        store.putMemory(AnnotationTarget.PLACE, 30, "fact", "x")
        store.putMemory(AnnotationTarget.PLACE, 30, "hunch", "y", 2f)

        val memories = store.getMemories(AnnotationTarget.PLACE, 30)
        assertEquals(1f, memories.getValue("fact").confidence, 0f)
        assertEquals(1f, memories.getValue("hunch").confidence, 0f) // clamped to [0,1]
        assertNotNull(memories.getValue("fact").updatedAtMs) // every write stamps its entry
    }

    @Test
    fun loadEdits_emptyTargetIsBlank() = runBlocking {
        val loaded = store.loadEdits(AnnotationTarget.VISIT, 999)
        assertEquals("", loaded.note)
        assertTrue(loaded.tags.isEmpty())
        assertTrue(loaded.memories.isEmpty())
    }

    // --- writer attribution ----------------------------------------------------------------------

    @Test
    fun apiWrites_attributeWriter_uiWritesStayNull() = runBlocking {
        store.setNote(AnnotationTarget.PLACE, 40, "agent text", writer = "com.example.agent")
        assertEquals(
            "com.example.agent",
            annotationDao.byTarget(AnnotationTarget.PLACE, 40, AnnotationKind.NOTE)!!.updatedBy,
        )

        // The in-app editor writes with no writer -> attribution becomes null (the user did it).
        store.saveEdits(AnnotationTarget.PLACE, 40, note = "user text", tags = emptyList())
        assertNull(annotationDao.byTarget(AnnotationTarget.PLACE, 40, AnnotationKind.NOTE)!!.updatedBy)

        store.putMemory(AnnotationTarget.PLACE, 40, "k", "v", writer = "com.example.agent")
        assertEquals(
            "com.example.agent",
            store.getMemories(AnnotationTarget.PLACE, 40).getValue("k").updatedBy,
        )
    }

    @Test
    fun saveEdits_untouchedEditorPreservesAttribution() = runBlocking {
        store.setNote(AnnotationTarget.PLACE, 41, "agent note", writer = "com.example.agent")
        store.applyTag(AnnotationTarget.PLACE, 41, "Agent Tag", writer = "com.example.agent")

        // Open-and-save with nothing changed: the note text and tag set are identical, so no row
        // is rewritten and the agent's attribution survives "just viewing".
        store.saveEdits(AnnotationTarget.PLACE, 41, note = "agent note", tags = listOf("Agent Tag"))

        assertEquals(
            "com.example.agent",
            annotationDao.byTarget(AnnotationTarget.PLACE, 41, AnnotationKind.NOTE)!!.updatedBy,
        )
        assertEquals(
            "com.example.agent",
            tagDao.linksFor(AnnotationTarget.PLACE, 41).single().createdBy,
        )
    }

    @Test
    fun applyTag_reApplyRefreshesSpellingButNeverReAttributes() = runBlocking {
        store.applyTag(AnnotationTarget.PLACE, 42, "Coffee Shop", writer = null) // user-created
        store.applyTag(AnnotationTarget.PLACE, 42, "COFFEE_SHOP", writer = "com.example.agent")

        val tag = tagDao.byCanonicalName("coffee-shop")!!
        assertEquals("COFFEE_SHOP", tag.displayName) // spelling refreshed
        assertNull(tag.createdBy)                    // creator unchanged (the user)
        assertNull(tagDao.linksFor(AnnotationTarget.PLACE, 42).single().createdBy) // link too
    }

    @Test
    fun memories_equalValuesFoldKeepsWinningClaimsWriterVerbatim() = runBlocking {
        store.putMemory(AnnotationTarget.VISIT, 50, "k", "same", 0.5f, writer = "com.weak.app")
        store.putMemory(AnnotationTarget.VISIT, 51, "k", "same", 0.8f, writer = "com.strong.app")

        store.foldOnMerge(AnnotationTarget.VISIT, survivorId = 50, dyingId = 51)

        // The stronger claim's writer travels with its value/confidence/source.
        assertEquals(
            "com.strong.app",
            store.getMemories(AnnotationTarget.VISIT, 50).getValue("k").updatedBy,
        )
    }

    @Test
    fun memories_differingValuesFoldIsPathlinesComposite() = runBlocking {
        store.putMemory(AnnotationTarget.VISIT, 60, "k", "old", 0.9f, writer = "com.app.a")
        store.putMemory(AnnotationTarget.VISIT, 61, "k", "new", 0.4f, writer = "com.app.b")

        store.foldOnMerge(AnnotationTarget.VISIT, survivorId = 60, dyingId = 61)

        // No single app wrote the concatenation; the row-level writer is Pathline (null) too.
        assertNull(store.getMemories(AnnotationTarget.VISIT, 60).getValue("k").updatedBy)
        assertNull(annotationDao.byTarget(AnnotationTarget.VISIT, 60, AnnotationKind.MEMORY)!!.updatedBy)
    }

    @Test
    fun foldOnMerge_unionsConceptMemberships() = runBlocking {
        val conceptStore = ConceptStore(conceptDao, store)
        val c = conceptStore.create("Japan Trip", "trip", null, writer = "com.example.agent")
        conceptStore.addMember(c.id, AnnotationTarget.VISIT, 70, writer = "com.example.agent")
        conceptStore.addMember(c.id, AnnotationTarget.VISIT, 71, writer = "com.example.agent")

        store.foldOnMerge(AnnotationTarget.VISIT, survivorId = 70, dyingId = 71)

        val members = conceptDao.membersOf(c.id)
        assertEquals(1, members.size) // union collapsed the duplicate membership
        assertEquals(70L, members.single().targetId)
    }

    @Test
    fun cascadeDelete_dropsConceptMembershipsOfTheTarget() = runBlocking {
        val conceptStore = ConceptStore(conceptDao, store)
        val c = conceptStore.create("Errands", null, null)
        conceptStore.addMember(c.id, AnnotationTarget.PLACE, 80)

        store.cascadeDelete(AnnotationTarget.PLACE, 80)

        assertTrue(conceptDao.membersOf(c.id).isEmpty())
        assertNotNull(conceptDao.byId(c.id)) // the concept itself survives, just emptier
    }
}
