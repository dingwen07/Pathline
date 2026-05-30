package net.extrawdw.apps.locationhistory.backup

import android.net.Uri
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
    private val repo: BackupRepository,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val _state = MutableStateFlow<ManagedState?>(null)
    val state: StateFlow<ManagedState?> = _state.asStateFlow()
    private var job: Job? = null

    fun startConfigure(uri: Uri, subdir: String?) =
        launch(ManagedKind.BACKUP, "Setting up backup") { repo.configureDestination(uri, subdir, it) }

    fun startBackup() =
        launch(ManagedKind.BACKUP, "Backing up") { repo.runManagedBackup(it) }

    fun startEnablePassword(password: CharArray) =
        launch(ManagedKind.BACKUP, "Enabling encryption") { repo.enablePasswordEncryption(password, it) }

    fun startEnablePasskey(choice: EncryptionChoice.Passkey) =
        launch(ManagedKind.BACKUP, "Enabling passkey encryption") { repo.enablePasskeyEncryption(choice, it) }

    fun startDisableEncryption() =
        launch(ManagedKind.BACKUP, "Removing encryption") { repo.disableEncryption(it) }

    fun startDump(uri: Uri, subdir: String?, choice: EncryptionChoice) =
        launch(ManagedKind.DUMP, "Creating database dump") { repo.oneTimeDump(uri, subdir, choice, it) }

    fun startRestore(uri: Uri, password: CharArray?, prfSecret: ByteArray?) =
        launch(ManagedKind.RESTORE, "Restoring") { repo.restoreFrom(uri, password, prfSecret, it) }

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
            "Done — ${result.report.partitionsWritten} partition(s) written" +
                if (result.report.partitionsFailed > 0) ", ${result.report.partitionsFailed} failed" else ""
        is BackupResult.Restored -> "Restored ${result.report.rowsRestored} rows from ${result.report.partitionsRestored} partition(s)"
        BackupResult.NoDestination -> "Choose a backup folder first"
        BackupResult.NeedsReclaim -> "Backup folder is no longer accessible — reconnect it"
        BackupResult.KeyUnavailable -> "Backup key unavailable — re-enter your password/passkey"
        is BackupResult.Error -> "Failed: ${result.message}"
    }

    private companion object {
        const val TAG = "ManagedBackup"
        const val MAX_LOG_LINES = 300
    }
}
