package net.extrawdw.apps.locationhistory.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide mutual exclusion for derived-timeline writes. The maintenance rebuild deletes and
 * re-inserts unconfirmed visits/trips wholesale; a user confirmation or hand edit interleaving with
 * that sweep can act on a row the rebuild just deleted (the confirm's `@Update` then silently hits
 * zero rows and the user's input vanishes). Every mutating flow takes this lock, so a confirm/edit
 * and a rebuild can never overlap.
 *
 * The wrapped [Mutex] is NOT reentrant, so the lock is held at the OUTERMOST entry points only:
 *  - the whole rebuild invocation in `TimelineMaintenanceWorker`;
 *  - `TimelineRepository.confirmVisitPlace` / `confirmTripMode`;
 *  - [TimelineEditor.splitItem] / [TimelineEditor.convertItemType].
 *  - place editor writes and legacy coordinate classification/repair.
 * None of these calls into another locked entry point — the editor only *enqueues* maintenance
 * work (which runs later, in the worker, on its own lock acquisition) — so the non-reentrant mutex
 * cannot self-deadlock. Keep it that way when adding callers.
 */
@Singleton
class TimelineWriteLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
