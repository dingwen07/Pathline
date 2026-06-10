package net.extrawdw.apps.locationhistory.domain

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Domain tests for [ConceptStore] against the in-memory fakes: the explicit lifecycle (create with
 * canonical-collision errors, partial update, delete cascade), kind canonicalization, the
 * no-nesting rule, and membership semantics.
 */
class ConceptStoreTest {

    private val tagDao = FakeTagDao()
    private val annotationDao = FakeAnnotationDao()
    private val conceptDao = FakeConceptDao()
    private val annotationStore = AnnotationStore(tagDao, annotationDao, conceptDao)
    private val store = ConceptStore(conceptDao, annotationStore)

    @Test
    fun create_canonicalizesNameAndKind() = runBlocking {
        val c = store.create("  Japan Trip 2026 ", kind = "TRIP", description = " spring break ")
        assertEquals("Japan Trip 2026", c.displayName)
        assertEquals("japan-trip-2026", c.canonicalName)
        assertEquals("trip", c.kind)
        assertEquals("spring break", c.description)
        assertEquals(c.createdAtMs, c.updatedAtMs)
    }

    @Test
    fun create_collidingCanonicalNameIsAnErrorNamingTheExistingId() = runBlocking {
        val existing = store.create("My Gyms")
        try {
            store.create("my_gyms", kind = "places")
            fail("expected a collision error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("id ${existing.id}"))
        }
    }

    @Test
    fun update_isPartialAndOnlyBumpsOnRealChange() = runBlocking {
        val c = store.create("Client Sites", kind = "project", writer = "com.app.a")

        // Setting only the description leaves name/kind; updatedBy moves to the editor.
        val edited = store.update(
            c.id, description = "places I bill for", setDescription = true, writer = "com.app.b",
        )!!
        assertEquals("Client Sites", edited.displayName)
        assertEquals("project", edited.kind)
        assertEquals("places I bill for", edited.description)
        assertEquals("com.app.a", edited.createdBy)
        assertEquals("com.app.b", edited.updatedBy)

        // A no-op update changes nothing (same row back, attribution untouched).
        val noop = store.update(c.id, writer = "com.app.c")!!
        assertEquals(edited, noop)

        // Clearing kind: key present with null.
        assertNull(store.update(c.id, kind = null, setKind = true)!!.kind)
    }

    @Test
    fun update_renameCollisionThrows_renameToSelfIsFine() = runBlocking {
        store.create("Alpha")
        val b = store.create("Beta")
        try {
            store.update(b.id, displayName = "ALPHA")
            fail("expected a collision error")
        } catch (expected: IllegalArgumentException) {
        }
        // Re-spelling the same canonical name is allowed (it's the same identity).
        assertEquals("BETA", store.update(b.id, displayName = "BETA")!!.displayName)
    }

    @Test
    fun delete_cascadesMembershipsAndOwnAnnotations() = runBlocking {
        val c = store.create("Wedding Weekend", kind = "event")
        store.addMember(c.id, AnnotationTarget.VISIT, 1)
        annotationStore.applyTag(AnnotationTarget.CONCEPT, c.id, "family")
        annotationStore.setNote(AnnotationTarget.CONCEPT, c.id, "two days")
        annotationStore.putMemory(AnnotationTarget.CONCEPT, c.id, "venue", "lakeside")

        assertTrue(store.delete(c.id))

        assertNull(conceptDao.byId(c.id))
        assertTrue(conceptDao.membersOf(c.id).isEmpty())
        assertTrue(annotationStore.tagsFor(AnnotationTarget.CONCEPT, c.id).isEmpty())
        assertNull(annotationStore.getNote(AnnotationTarget.CONCEPT, c.id))
        assertTrue(annotationStore.getMemories(AnnotationTarget.CONCEPT, c.id).isEmpty())
        assertFalse(store.delete(c.id)) // already gone
    }

    @Test
    fun addMember_rejectsNestingAndUnknownConcepts_reAddKeepsOriginalAttribution() = runBlocking {
        val c = store.create("Trips North", writer = "com.app.a")
        try {
            store.addMember(c.id, AnnotationTarget.CONCEPT, 99)
            fail("expected the no-nesting rule")
        } catch (expected: IllegalArgumentException) {
        }
        assertFalse(store.addMember(999, AnnotationTarget.VISIT, 1))

        assertTrue(store.addMember(c.id, AnnotationTarget.VISIT, 1, writer = "com.app.a"))
        assertTrue(store.addMember(c.id, AnnotationTarget.VISIT, 1, writer = "com.app.b")) // no-op
        val member = conceptDao.membersOf(c.id).single()
        assertEquals("com.app.a", member.createdBy) // first attach wins

        assertEquals(1, store.removeMember(c.id, AnnotationTarget.VISIT, 1))
        assertEquals(0, store.removeMember(c.id, AnnotationTarget.VISIT, 1))
    }

    @Test
    fun conceptsAreAnnotatable_likeAnyOtherTarget() = runBlocking {
        val c = store.create("Foodie Map")
        annotationStore.applyTag(AnnotationTarget.CONCEPT, c.id, "Hobby", writer = "com.app.a")
        val tag = annotationStore.tagsFor(AnnotationTarget.CONCEPT, c.id).single()
        assertEquals("Hobby", tag.displayName)
        assertNotNull(tagDao.linksFor(AnnotationTarget.CONCEPT, c.id).single().createdBy)
    }
}
