package net.extrawdw.apps.locationhistory.ui

import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.extrawdw.apps.locationhistory.data.db.PlaceEntity
import net.extrawdw.apps.locationhistory.data.db.VisitEntity

// These are small, single-row snapshots. Preserve the editor's original geometry rather than
// reloading a potentially updated place as the baseline of an in-progress edit.
internal val PlaceDialogSaver = nullableRowSaver(PlaceEntity.serializer())
internal val VisitDialogSaver = nullableRowSaver(VisitEntity.serializer())
internal val PlaceSearchAnchorSaver = Saver<PlaceSearchAnchor?, List<Double>>(
    save = { it?.let { anchor -> listOf(anchor.latitude, anchor.longitude) } ?: emptyList() },
    restore = { it.takeIf { it.size == 2 }?.let { values -> PlaceSearchAnchor(values[0], values[1]) } },
)

private fun <T> nullableRowSaver(serializer: KSerializer<T>): Saver<T?, String> = Saver(
    save = { row -> row?.let { Json.encodeToString(serializer, it) } ?: "null" },
    restore = { value -> if (value == "null") null else Json.decodeFromString(serializer, value) },
)
