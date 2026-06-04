package net.extrawdw.apps.locationhistory.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import net.extrawdw.apps.locationhistory.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.data.repo.BackupRepository
import net.extrawdw.apps.locationhistory.data.repo.BackupResult
import net.extrawdw.apps.locationhistory.data.repo.EncryptionChoice
import javax.inject.Inject
import javax.inject.Singleton

enum class ManagedKind { BACKUP, DUMP, RESTORE }

/** Live state of a managed (UI-driven) backup/restore, rendered by the managed sheet. */
data class ManagedState(
    val kind: ManagedKind,
    val title: String,
    val running: Boolean,
    /** 0..1 determinate, or negative for indeterminate. */
    val progress: Float,
    val logs: List<String>,
    val finished: Boolean,
    val success: Boolean,
    val message: String?,
)

/**
 * Singleton that runs one managed backup/restore at a time on its own scope (so it survives sheet
 * recomposition / dismissal) and exposes a [StateFlow] the UI observes for the progress bar and log.
 *
 * Passkey ceremonies need an Activity and are run by the caller (the ViewModel) before invoking the
 * dump/restore/enable methods here — this controller only deals with already-decided crypto choices.
 */
@Singleton
class BackupOperationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: BackupRepository,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val _state = MutableStateFlow<ManagedState?>(null)
    val state: StateFlow<ManagedState?> = _state.asStateFlow()
    private var job: Job? = null

    fun startConfigure(uri: Uri, subdir: String?) =
        launch(ManagedKind.BACKUP, context.getString(R.string.backup_op_setup)) { repo.configureDestination(uri, subdir, it) }

    fun startBackup() =
        launch(ManagedKind.BACKUP, context.getString(R.string.backup_op_backing_up)) { repo.runManagedBackup(it) }

    fun startEnablePassword(password: CharArray) =
        launch(ManagedKind.BACKUP, context.getString(R.string.backup_op_enable_encryption)) { repo.enablePasswordEncryption(password, it) }

    fun startEnablePasskey(choice: EncryptionChoice.Passkey) =
        launch(ManagedKind.BACKUP, context.getString(R.string.backup_op_enable_passkey)) { repo.enablePasskeyEncryption(choice, it) }

    fun startDisableEncryption() =
        launch(ManagedKind.BACKUP, context.getString(R.string.backup_op_remove_encryption)) { repo.disableEncryption(it) }

    fun startDump(uri: Uri, subdir: String?, choice: EncryptionChoice) =
        launch(ManagedKind.DUMP, context.getString(R.string.backup_op_dump)) { repo.oneTimeDump(uri, subdir, choice, it) }

    fun startRestore(uri: Uri, password: CharArray?, prfSecret: ByteArray?) =
        launch(ManagedKind.RESTORE, context.getString(R.string.backup_op_restore)) { repo.restoreFrom(uri, password, prfSecret, it) }

    /** Surface a ceremony/setup failure (e.g. passkey cancelled) in the managed sheet. */
    fun fail(kind: ManagedKind, title: String, message: String) {
        _state.value = ManagedState(kind, title, running = false, progress = 0f, logs = listOf(message),
            finished = true, success = false, message = message)
    }

    fun dismiss() {
        if (_state.value?.running != true) _state.value = null
    }

    private fun launch(kind: ManagedKind, title: String, op: suspend (BackupReporter) -> BackupResult) {
        if (_state.value?.running == true) return
        _state.value = ManagedState(kind, title, running = true, progress = -1f, logs = emptyList(),
            finished = false, success = false, message = null)
        job = scope.launch {
            val reporter = object : BackupReporter {
                override fun log(message: String) {
                    AppLog.i(TAG, message)
                    _state.update { s -> s?.copy(logs = (s.logs + message).takeLast(MAX_LOG_LINES)) }
                }
                override fun progress(fraction: Float) {
                    _state.update { s -> s?.copy(progress = fraction) }
                }
            }
            val result = op(reporter)
            val ok = result is BackupResult.Backed || result is BackupResult.Restored
            _state.update { s ->
                s?.copy(running = false, finished = true, progress = if (ok) 1f else s.progress,
                    success = ok, message = describe(result))
            }
        }
    }

    private fun describe(result: BackupResult): String = when (result) {
        is BackupResult.Backed ->
            if (result.report.partitionsFailed > 0) {
                context.getString(R.string.backup_result_backed_failed, result.report.partitionsWritten, result.report.partitionsFailed)
            } else {
                context.getString(R.string.backup_result_backed, result.report.partitionsWritten)
            }
        is BackupResult.Restored -> context.getString(R.string.backup_result_restored, result.report.rowsRestored, result.report.partitionsRestored)
        BackupResult.NoDestination -> context.getString(R.string.backup_result_no_destination)
        BackupResult.NeedsReclaim -> context.getString(R.string.backup_result_needs_reclaim)
        BackupResult.KeyUnavailable -> context.getString(R.string.backup_result_key_unavailable)
        is BackupResult.Error -> context.getString(R.string.backup_result_failed, result.message)
    }

    private companion object {
        const val TAG = "ManagedBackup"
        const val MAX_LOG_LINES = 300
    }
}
