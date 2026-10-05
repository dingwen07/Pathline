package net.extrawdw.apps.locationhistory.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.extrawdw.apps.locationhistory.backup.BackupOperationController
import net.extrawdw.apps.locationhistory.data.db.ApiAccessDatabase
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.security.MapsApiKeyVault
import net.extrawdw.apps.locationhistory.service.FirebaseTelemetry
import net.extrawdw.apps.locationhistory.service.RecordingController
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

data class ResetDataOptions(
    val appSettings: Boolean = false,
    val mapsPlatformSettings: Boolean = false,
    val logs: Boolean = false,
    val onboarding: Boolean = true,
)

/** Return to setup with an empty database and no background writer able to repopulate it. */
@Singleton
class RecordedDataResetter @Inject constructor(
    private val manager: DataManager,
    private val settings: SettingsRepository,
    private val recording: RecordingController,
    private val scheduler: WorkScheduler,
    private val accessDb: ApiAccessDatabase,
    private val backups: BackupOperationController,
    private val mapsKey: MapsApiKeyVault,
    private val apiAccess: ApiAccessRepository,
    @param:ApplicationContext private val context: Context,
) {
    suspend fun preview(options: ResetDataOptions): DataDeletionSummary = withContext(Dispatchers.IO) {
        manager.previewReset().copy(
            logFiles = if (options.logs) AppLog.logFileCount() else null,
            dataApiLogs = if (options.logs) accessDb.apiAccessDao().countSince(Long.MIN_VALUE) else null,
        )
    }

    suspend fun reset(options: ResetDataOptions) = withContext(Dispatchers.IO + NonCancellable) {
        // Once accepted, finish even when publishing the onboarding gate removes the caller's UI.
        settings.prepareForDataReset()
        recording.resetTracking()
        scheduler.cancelForDataReset()
        // Old grants must not authorize reused place identities in a fresh database or restore.
        accessDb.apiPlaceGrantDao().clearAll()
        manager.resetAllData()
        backups.dismiss()
        if (options.mapsPlatformSettings) mapsKey.clear()
        if (options.appSettings) {
            apiAccess.resetSettings()
            FirebaseTelemetry.apply(true)
        }
        if (options.logs) {
            apiAccess.clearAllLogs()
            AppLog.deleteAllLogs(context)
        }
        settings.completeDataReset(options)
    }
}
