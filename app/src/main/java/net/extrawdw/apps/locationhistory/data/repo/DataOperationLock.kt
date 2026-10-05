package net.extrawdw.apps.locationhistory.data.repo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Keep backup snapshots/dirty-marker acknowledgement and explicit purges mutually exclusive. */
@Singleton
class DataOperationLock @Inject constructor() {
    @PublishedApi internal val mutex = Mutex()
    suspend inline fun <T> withLock(block: () -> T): T = mutex.withLock { block() }
}
