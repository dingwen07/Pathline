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
 * canonical-collision errors, partial update, delete cascade), kind canonicalization, nesting with
 * the membership cycle rule, the archive flag, and membership semantics.
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
    fun addMember_rejectsUnknownConcepts_reAddKeepsOriginalAttribution() = runBlocking {
        val c = store.create("Trips North", writer = "com.app.a")
        assertFalse(store.addMember(999, AnnotationTarget.VISIT, 1))

        assertTrue(store.addMember(c.id, AnnotationTarget.VISIT, 1, writer = "com.app.a"))
        assertTrue(store.addMember(c.id, AnnotationTarget.VISIT, 1, writer = "com.app.b")) // no-op
        val member = conceptDao.membersOf(c.id).single()
        assertEquals("com.app.a", member.createdBy) // first attach wins

        assertEquals(1, store.removeMember(c.id, AnnotationTarget.VISIT, 1))
        assertEquals(0, store.removeMember(c.id, AnnotationTarget.VISIT, 1))
    }

    @Test
    fun addMember_nestsConceptsButRejectsCycles() = runBlocking {
        val travel = store.create("Travel")
        val japan = store.create("Japan Trip 2026")
        val tokyo = store.create("Tokyo Days")

        // A chain nests fine: travel > japan > tokyo.
        assertTrue(store.addMember(travel.id, AnnotationTarget.CONCEPT, japan.id))
        assertTrue(store.addMember(japan.id, AnnotationTarget.CONCEPT, tokyo.id))

        // Self-membership and both direct and transitive cycles are rejected.
        for ((parent, child) in listOf(
            travel.id to travel.id, // self
            japan.id to travel.id, // direct: travel already contains japan
            tokyo.id to travel.id, // transitive: travel > japan > tokyo
        )) {
            try {
                store.addMember(parent, AnnotationTarget.CONCEPT, child)
                fail("expected a cycle error for $child into $parent")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("cycle"))
            }
        }

        // A diamond is not a cycle: a second parent of tokyo is fine.
        val highlights = store.create("Highlights")
        assertTrue(store.addMember(highlights.id, AnnotationTarget.CONCEPT, tokyo.id))

        // Deleting a nested concept detaches it from its parents (cascade goes both directions).
        assertTrue(store.delete(tokyo.id))
        assertTrue(conceptDao.membersOf(japan.id).none { it.targetType == AnnotationTarget.CONCEPT })
        assertTrue(conceptDao.membersOf(highlights.id).isEmpty())
    }

    @Test
    fun setArchived_isAVisibilityFlagNotADelete() = runBlocking {
        val c = store.create("Old Gyms", writer = "com.app.a")
        store.addMember(c.id, AnnotationTarget.PLACE, 7)

        val archived = store.setArchived(c.id, true, writer = "com.app.b")!!
        assertNotNull(archived.archivedAtMs)
        assertEquals("com.app.b", archived.archivedBy)
        assertEquals(c.updatedAtMs, archived.updatedAtMs) // not an intrinsic edit
        assertEquals(1, conceptDao.membersOf(c.id).size) // members untouched

        // Already-in-state is a no-op keeping the original stamp.
        val again = store.setArchived(c.id, true, writer = "com.app.c")!!
        assertEquals(archived.archivedAtMs, again.archivedAtMs)
        assertEquals("com.app.b", again.archivedBy)

        // Unarchive clears both stamps; a missing id is null.
        val active = store.setArchived(c.id, false)!!
        assertNull(active.archivedAtMs)
        assertNull(active.archivedBy)
        assertNull(store.setArchived(999, true))
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
