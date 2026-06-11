package net.extrawdw.apps.locationhistory.domain

import net.extrawdw.apps.locationhistory.core.AnnotationKind
import net.extrawdw.apps.locationhistory.core.AnnotationTarget
import net.extrawdw.apps.locationhistory.data.db.AnnotationDao
import net.extrawdw.apps.locationhistory.data.db.AnnotationEntity
import net.extrawdw.apps.locationhistory.data.db.ConceptDao
import net.extrawdw.apps.locationhistory.data.db.ConceptEntity
import net.extrawdw.apps.locationhistory.data.db.ConceptMemberCount
import net.extrawdw.apps.locationhistory.data.db.ConceptMemberEntity
import net.extrawdw.apps.locationhistory.data.db.EntityTagEntity
import net.extrawdw.apps.locationhistory.data.db.TagDao
import net.extrawdw.apps.locationhistory.data.db.TagEntity

// In-memory DAO fakes shared by the annotation/concept domain tests. They mirror the SQL semantics
// the stores rely on (insert-ignore, OR IGNORE rekey unions, unique canonical names).

internal class FakeTagDao : TagDao {
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

    override suspend fun linksFor(type: AnnotationTarget, id: Long): List<EntityTagEntity> =
        links.filter { it.targetType == type && it.targetId == id }

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

internal class FakeAnnotationDao : AnnotationDao {
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

internal class FakeConceptDao : ConceptDao {
    private val concepts = mutableListOf<ConceptEntity>()
    private val members = mutableListOf<ConceptMemberEntity>()
    private var nextId = 1L

    override suspend fun insertIgnore(concept: ConceptEntity): Long {
        if (concepts.any { it.canonicalName == concept.canonicalName }) return -1
        val row = concept.copy(id = nextId++)
        concepts.add(row)
        return row.id
    }

    override suspend fun update(concept: ConceptEntity) {
        concepts.replaceAll { if (it.id == concept.id) concept else it }
    }

    override suspend fun byId(id: Long): ConceptEntity? = concepts.firstOrNull { it.id == id }

    override suspend fun byCanonicalName(canonicalName: String): ConceptEntity? =
        concepts.firstOrNull { it.canonicalName == canonicalName }

    override suspend fun all(): List<ConceptEntity> = concepts.sortedBy { it.displayName }

    override suspend fun byKind(kind: String): List<ConceptEntity> =
        concepts.filter { it.kind == kind }.sortedBy { it.displayName }

    override suspend fun byIds(ids: List<Long>): List<ConceptEntity> =
        concepts.filter { it.id in ids.toSet() }.sortedBy { it.displayName }

    override suspend fun delete(id: Long): Int {
        val before = concepts.size
        concepts.removeAll { it.id == id }
        return before - concepts.size
    }

    override suspend fun addMember(member: ConceptMemberEntity) {
        val exists = members.any {
            it.conceptId == member.conceptId && it.targetType == member.targetType &&
                it.targetId == member.targetId
        }
        if (!exists) members.add(member)
    }

    override suspend fun membersOf(conceptId: Long): List<ConceptMemberEntity> =
        members.filter { it.conceptId == conceptId }
            .sortedWith(compareBy({ it.createdAtMs }, { it.targetType }, { it.targetId }))

    override suspend fun memberCounts(): List<ConceptMemberCount> =
        members.groupBy { it.conceptId }.map { (id, rows) -> ConceptMemberCount(id, rows.size) }

    override suspend fun membershipsFor(type: AnnotationTarget, id: Long): List<ConceptMemberEntity> =
        members.filter { it.targetType == type && it.targetId == id }

    override suspend fun conceptsFor(type: AnnotationTarget, id: Long): List<ConceptEntity> {
        val ids = members.filter { it.targetType == type && it.targetId == id }
            .map { it.conceptId }.toSet()
        return concepts.filter { it.id in ids }.sortedBy { it.displayName }
    }

    override suspend fun removeMember(conceptId: Long, type: AnnotationTarget, id: Long): Int {
        val before = members.size
        members.removeAll { it.conceptId == conceptId && it.targetType == type && it.targetId == id }
        return before - members.size
    }

    override suspend fun removeAllMembers(conceptId: Long) {
        members.removeAll { it.conceptId == conceptId }
    }

    override suspend fun removeMembersForTarget(type: AnnotationTarget, id: Long) {
        members.removeAll { it.targetType == type && it.targetId == id }
    }

    override suspend fun rekeyMembers(type: AnnotationTarget, fromId: Long, toId: Long) {
        val moved = members.filter { it.targetType == type && it.targetId == fromId }
        members.removeAll { it.targetType == type && it.targetId == fromId }
        for (m in moved) {
            val collide = members.any {
                it.conceptId == m.conceptId && it.targetType == type && it.targetId == toId
            }
            if (!collide) members.add(m.copy(targetId = toId))
        }
    }
}
