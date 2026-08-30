package net.extrawdw.apps.locationhistory.data.db

import androidx.room3.Entity
import androidx.room3.Fts5

/** Room-owned external-content FTS5 index over the searchable columns of [PlaceEntity]. */
@Entity(tableName = "places_fts")
@Fts5(contentEntity = PlaceEntity::class, contentRowId = "id")
data class PlaceFtsEntity(
    val name: String,
    val address: String?,
    val category: String?,
    val types: String?,
)

/** Room-owned external-content FTS5 index over [TagEntity.displayName]. */
@Entity(tableName = "tags_fts")
@Fts5(contentEntity = TagEntity::class, contentRowId = "id")
data class TagFtsEntity(
    val displayName: String,
)

/** Room-owned external-content FTS5 index over the searchable columns of [ConceptEntity]. */
@Entity(tableName = "concepts_fts")
@Fts5(contentEntity = ConceptEntity::class, contentRowId = "id")
data class ConceptFtsEntity(
    val displayName: String,
    val kind: String?,
    val description: String?,
)
