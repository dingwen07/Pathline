package net.extrawdw.apps.locationhistory.data.repo

import net.extrawdw.apps.locationhistory.data.db.PlaceEntity

/** Unordered identity; creation stamps keep a restored, unrelated row ID from inheriting an ignore. */
internal fun placeMergePairKey(first: PlaceEntity, second: PlaceEntity): String {
    val (a, b) = if (first.id < second.id) first to second else second to first
    return "${a.id}@${a.createdAtMs}:${b.id}@${b.createdAtMs}"
}
