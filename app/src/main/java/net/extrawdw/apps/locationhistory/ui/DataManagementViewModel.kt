package net.extrawdw.apps.locationhistory.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.data.db.AppDatabase
import net.extrawdw.apps.locationhistory.data.repo.DataManager
import net.extrawdw.apps.locationhistory.data.repo.DeletePlaceOptions
import net.extrawdw.apps.locationhistory.data.repo.EdgeActivityBehavior
import net.extrawdw.apps.locationhistory.data.repo.RecordedDataResetter
import net.extrawdw.apps.locationhistory.data.repo.ResetDataOptions
import javax.inject.Inject

internal enum class DataDeletionStatus { IDLE, RUNNING, DONE, FAILED }

@HiltViewModel
class DataManagementViewModel @Inject constructor(
    private val manager: DataManager,
    db: AppDatabase,
    private val resetter: dagger.Lazy<RecordedDataResetter>,
) : ViewModel() {
    internal val places = combine(db.placeDao().observeAll(),
        db.locationSampleDao().observeMostRecent(), db.visitDao().observeMostRecent()) { places, sample, visit ->
        rankDeletionPlaces(places, sample, visit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _status = MutableStateFlow(DataDeletionStatus.IDLE)
    internal val status = _status.asStateFlow()

    internal fun dismissResult() {
        if (_status.value != DataDeletionStatus.RUNNING) _status.value = DataDeletionStatus.IDLE
    }

    internal fun deleteDateRange(first: Long, last: Long, edge: EdgeActivityBehavior, emptyPlaces: Boolean) =
        perform { manager.deleteDateRange(first, last, edge, emptyPlaces) }

    internal fun deletePlace(id: Long, options: DeletePlaceOptions) = perform {
        check(manager.deletePlace(id, options)) { "Place no longer exists" }
    }

    internal fun resetAllData(options: ResetDataOptions) = perform { resetter.get().reset(options) }

    internal suspend fun previewRange(first: Long, last: Long, edge: EdgeActivityBehavior, emptyPlaces: Boolean) =
        manager.previewDateRange(first, last, edge, emptyPlaces)
    internal suspend fun previewPlace(id: Long, options: DeletePlaceOptions) = manager.previewPlace(id, options)
    internal suspend fun previewReset(options: ResetDataOptions) = resetter.get().preview(options)

    private fun perform(block: suspend () -> Unit) {
        if (_status.value == DataDeletionStatus.RUNNING) return
        _status.value = DataDeletionStatus.RUNNING
        // Owned by the ViewModel so rotation and a departing caller do not cancel an accepted purge.
        viewModelScope.launch {
            try {
                block()
                _status.value = DataDeletionStatus.DONE
            } catch (cancelled: CancellationException) {
                _status.value = DataDeletionStatus.IDLE
                throw cancelled
            } catch (error: Exception) {
                AppLog.w("DataManagement", "Deletion failed: ${error.javaClass.simpleName}")
                _status.value = DataDeletionStatus.FAILED
            }
        }
    }
}
