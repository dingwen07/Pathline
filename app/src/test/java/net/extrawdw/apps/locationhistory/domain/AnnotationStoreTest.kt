package net.extrawdw.apps.locationhistory.domain

import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.AnnotationDao
import net.extrawdw.apps.locationhistory.data.db.AnnotationEntity
import net.extrawdw.apps.locationhistory.data.db.EntityTagEntity
import net.extrawdw.apps.locationhistory.data.db.TagDao
import net.extrawdw.apps.locationhistory.data.db.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the in-app editor's load/save path ([AnnotationStore.loadEdits] /
 * [AnnotationStore.saveEdits]) against in-memory fake DAOs — proving notes AND tags persist together,
 * tags carry the new hyphen canonical, and clearing/removal works. (DAO wiring itself is exercised by
 * on-device runs; this pins the domain contract the UI relies on.)
 */
class AnnotationStoreTest {

    private val store = AnnotationStore(FakeTagDao(), FakeAnnotationDao())

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

        assertEquals("my-home", TagCanonicalizer.canonicalize("My Home"))
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
}

// --- in-memory fakes -------------------------------------------------------------------------------

private class FakeTagDao : TagDao {
    private val tags = mutableListOf<TagEntity>()
    private val links = mutableListOf<EntityTagEntity>()
    private var nextId = 1L

    override suspend fun insertIgnore(tag: TagEntity): Long {
        if (tags.any { it.canonicalName == tag.canonicalName }) return -1
        val row = tag.copy(id = nextId++)
        tags.add(row)
        return row.id
    }

    override suspend fun byCanonicalName(canonicalName: String): TagEntity? =
        tags.firstOrNull { it.canonicalName == canonicalName }

    override suspend fun updateDisplayName(tagId: Long, displayName: String) {
        tags.replaceAll { if (it.id == tagId) it.copy(displayName = displayName) else it }
    }

    override suspend fun all(): List<TagEntity> = tags.sortedBy { it.displayName }

    override suspend fun byIds(ids: List<Long>): List<TagEntity> =
        tags.filter { it.id in ids.toSet() }.sortedBy { it.displayName }

    override suspend fun allLinks(): List<EntityTagEntity> = links.toList()

    override suspend fun targetIdsForTags(type: AnnotationTarget, tagIds: List<Long>): List<Long> =
        links.filter { it.targetType == type && it.tagId in tagIds.toSet() }
            .map { it.targetId }.distinct()

    override suspend fun link(link: EntityTagEntity) {
        val exists = links.any {
            it.tagId == link.tagId && it.targetType == link.targetType && it.targetId == link.targetId
        }
        if (!exists) links.add(link)
    }

    override suspend fun tagsFor(type: AnnotationTarget, id: Long): List<TagEntity> {
        val ids = links.filter { it.targetType == type && it.targetId == id }.map { it.tagId }.toSet()
        return tags.filter { it.id in ids }.sortedBy { it.displayName }
    }

    override suspend fun unlink(tagId: Long, type: AnnotationTarget, id: Long): Int {
        val before = links.size
        links.removeAll { it.tagId == tagId && it.targetType == type && it.targetId == id }
        return before - links.size
    }

    override suspend fun unlinkAll(type: AnnotationTarget, id: Long) {
        links.removeAll { it.targetType == type && it.targetId == id }
    }

    override suspend fun rekeyLinks(type: AnnotationTarget, fromId: Long, toId: Long) {
        val moved = links.filter { it.targetType == type && it.targetId == fromId }
        links.removeAll { it.targetType == type && it.targetId == fromId }
        for (l in moved) {
            val collide = links.any {
                it.tagId == l.tagId && it.targetType == type && it.targetId == toId
            }
            if (!collide) links.add(l.copy(targetId = toId))
        }
    }
}

private class FakeAnnotationDao : AnnotationDao {
    private val rows = mutableListOf<AnnotationEntity>()
    private var nextId = 1L

    override suspend fun upsert(annotation: AnnotationEntity): Long {
        rows.removeAll {
            it.targetType == annotation.targetType && it.targetId == annotation.targetId &&
                it.kind == annotation.kind
        }
        val row = annotation.copy(id = nextId++)
        rows.add(row)
        return row.id
    }

    override suspend fun update(annotation: AnnotationEntity) {
        rows.replaceAll { if (it.id == annotation.id) annotation else it }
    }

    override suspend fun byTarget(type: AnnotationTarget, id: Long, kind: AnnotationKind): AnnotationEntity? =
        rows.firstOrNull { it.targetType == type && it.targetId == id && it.kind == kind }

    override suspend fun allForTarget(type: AnnotationTarget, id: Long): List<AnnotationEntity> =
        rows.filter { it.targetType == type && it.targetId == id }

    override suspend fun allOfKind(type: AnnotationTarget, kind: AnnotationKind): List<AnnotationEntity> =
        rows.filter { it.targetType == type && it.kind == kind }

    override suspend fun targetIdsWithContentLike(
        type: AnnotationTarget,
        kind: AnnotationKind,
        pattern: String,
    ): List<Long> {
        // Mirror of `LIKE ? ESCAPE '\'` for a %text% pattern: unescape, then substring match.
        val needle = pattern.removePrefix("%").removeSuffix("%")
            .replace("\\%", "%").replace("\\_", "_").replace("\\\\", "\\")
        return rows.filter {
            it.targetType == type && it.kind == kind && it.content.contains(needle, ignoreCase = true)
        }.map { it.targetId }.distinct()
    }

    override suspend fun deleteForTargetKind(type: AnnotationTarget, id: Long, kind: AnnotationKind) {
        rows.removeAll { it.targetType == type && it.targetId == id && it.kind == kind }
    }

    override suspend fun deleteForTarget(type: AnnotationTarget, id: Long) {
        rows.removeAll { it.targetType == type && it.targetId == id }
    }
}
