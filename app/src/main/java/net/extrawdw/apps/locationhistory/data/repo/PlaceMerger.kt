package net.extrawdw.apps.locationhistory.data.repo

import androidx.room3.withWriteTransaction
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.domain.AnnotationStore
import net.extrawdw.apps.locationhistory.domain.TimelineWriteLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaceMerger @Inject constructor(
    private val db: AppDatabase,
    private val annotations: AnnotationStore,
    private val writeLock: TimelineWriteLock,
) {
    /** The destination's identity and geometry survive; all changes commit or roll back together. */
    suspend fun merge(sourceId: Long, destinationId: Long): Boolean = writeLock.withLock {
        if (sourceId == destinationId) return@withLock false
        // Acquire the memory mutex before the DB writer, matching API memory writes' lock order.
        annotations.withMemoryWriteLock {
            db.withWriteTransaction {
                val places = db.placeDao()
                if (places.byId(sourceId) == null || places.byId(destinationId) == null) {
                    return@withWriteTransaction false
                }
                annotations.foldPlaceOnMerge(destinationId, sourceId)
                db.visitDao().moveToPlace(sourceId, destinationId)
                check(places.deleteIfUnvisited(sourceId) == 1)
                true
            }
        }
    }
}
