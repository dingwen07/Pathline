package net.extrawdw.apps.locationhistory.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.backup.BackupOperationController
import net.extrawdw.apps.locationhistory.backup.ManagedKind
import net.extrawdw.apps.locationhistory.backup.ManagedState
import net.extrawdw.apps.locationhistory.data.repo.BackupConfig
import net.extrawdw.apps.locationhistory.data.repo.BackupRepository
import net.extrawdw.apps.locationhistory.data.repo.EncryptionChoice
import net.extrawdw.apps.locationhistory.security.BackupEncryption
import net.extrawdw.apps.locationhistory.security.PasskeyManager
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val controller: BackupOperationController,
    private val passkeyManager: PasskeyManager,
    private val backupRepository: BackupRepository,
    private val workScheduler: WorkScheduler,
) : ViewModel() {

    val config: StateFlow<BackupConfig?> = backupRepository.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val managed: StateFlow<ManagedState?> = controller.state

    fun dismissManaged() = controller.dismiss()

    fun folderLooksLikePathline(uri: Uri) = backupRepository.folderLooksLikePathline(uri)

    suspend fun backupExists(uri: Uri, subdir: String?): Boolean = backupRepository.backupExistsAt(uri, subdir)

    fun configure(uri: Uri, subdir: String?) {
        controller.startConfigure(uri, subdir)
        workScheduler.schedulePeriodicBackup()
    }

    fun backupNow() = controller.startBackup()
    fun enablePassword(password: CharArray) = controller.startEnablePassword(password)
    fun disableEncryption() = controller.startDisableEncryption()
    fun setGpxEnabled(enabled: Boolean) = viewModelScope.launch { backupRepository.setGpxEnabled(enabled) }
    fun onGpxFolderPicked(uri: Uri?) = viewModelScope.launch { backupRepository.setGpxTree(uri) }

    fun enablePasskey(activityContext: Context) = viewModelScope.launch {
        runCatching { passkeyManager.obtainForSetup(activityContext) }
            .onSuccess { controller.startEnablePasskey(EncryptionChoice.Passkey(it.secret, it.salt, it.credentialId)) }
            .onFailure { controller.fail(ManagedKind.BACKUP, "Passkey", it.message ?: "Passkey setup failed") }
    }

    fun dump(uri: Uri, subdir: String?, choice: EncryptionChoice) = controller.startDump(uri, subdir, choice)

    fun dumpWithPasskey(activityContext: Context, uri: Uri, subdir: String?) = viewModelScope.launch {
        runCatching { passkeyManager.obtainForSetup(activityContext) }
            .onSuccess { controller.startDump(uri, subdir, EncryptionChoice.Passkey(it.secret, it.salt, it.credentialId)) }
            .onFailure { controller.fail(ManagedKind.DUMP, "Passkey", it.message ?: "Passkey setup failed") }
    }
}

/** Backup section of the Settings screen. */
@Composable
fun BackupCard(viewModel: BackupViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsState()
    val activity = LocalContext.current

    // Pending folder choices that still need a subdir (and, for dump, an encryption choice).
    var pendingBackupUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDumpUri by remember { mutableStateOf<Uri?>(null) }
    var showEncryptionChooser by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }

    val pickBackupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let { uri -> pendingBackupUri = uri }
    }
    val pickGpxFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onGpxFolderPicked(it)
    }
    val pickDumpFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let { uri -> pendingDumpUri = uri }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            val cfg = config

            if (cfg?.treeUri == null) {
                Text(
                    "Back up your full timeline, places and trained models to a folder you choose " +
                        "(Google Drive, Dropbox, local storage). Only the latest week is re-uploaded each time.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { pickBackupFolder.launch(null) }) { Text("Choose backup folder") }
            } else {
                val dest = buildString {
                    append(Uri.parse(cfg.treeUri).lastPathSegment ?: "selected folder")
                    if (!cfg.subdir.isNullOrBlank()) append(" / ${cfg.subdir}")
                }
                Text("Backing up to: $dest", style = MaterialTheme.typography.bodySmall)
                if (cfg.lastBackupMs > 0) {
                    Text(
                        "Last backup: ${DateFormat.getDateTimeInstance().format(Date(cfg.lastBackupMs))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::backupNow) { Text("Back up now") }
                    OutlinedButton(onClick = { pickBackupFolder.launch(null) }) { Text("Change / reconnect") }
                }

                EncryptionRow(
                    mode = cfg.encryption,
                    onTurnOn = { showEncryptionChooser = true },
                    onTurnOff = viewModel::disableEncryption,
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Also export GPX", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Open, unencrypted weekly .gpx tracks under an \"export\" folder.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = cfg.gpxEnabled, onCheckedChange = viewModel::setGpxEnabled)
                }
                if (cfg.gpxEnabled) {
                    OutlinedButton(onClick = { pickGpxFolder.launch(null) }) {
                        Text(if (cfg.gpxTreeUri == null) "Use a separate GPX folder" else "Change GPX folder")
                    }
                }
            }

            Text(
                "One-time database dump writes a complete standalone copy to a folder you pick.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = { pickDumpFolder.launch(null) }) { Text("One-time dump…") }
        }
    }

    // Subdir prompt after choosing the backup destination.
    pendingBackupUri?.let { uri ->
        SubdirDialog(
            treeUri = uri,
            defaultLooksLikePathline = viewModel.folderLooksLikePathline(uri),
            checkExists = { sub -> viewModel.backupExists(uri, sub) },
            onConfirm = { subdir -> pendingBackupUri = null; viewModel.configure(uri, subdir) },
            onDismiss = { pendingBackupUri = null },
        )
    }

    // Encryption method chooser (turning encryption on).
    if (showEncryptionChooser) {
        EncryptionChooserDialog(
            onPassword = { showEncryptionChooser = false; showPasswordDialog = true },
            onPasskey = { showEncryptionChooser = false; viewModel.enablePasskey(activity) },
            onDismiss = { showEncryptionChooser = false },
        )
    }
    if (showPasswordDialog) {
        PasswordDialog(
            title = "Set a backup password",
            confirmLabel = "Encrypt",
            requireConfirm = true,
            onConfirm = { pw -> showPasswordDialog = false; viewModel.enablePassword(pw) },
            onDismiss = { showPasswordDialog = false },
        )
    }

    // Dump flow: subdir → encryption choice.
    pendingDumpUri?.let { uri ->
        DumpFlowDialogs(
            uri = uri,
            looksLikePathline = viewModel.folderLooksLikePathline(uri),
            checkExists = { sub -> viewModel.backupExists(uri, sub) },
            onNone = { subdir -> pendingDumpUri = null; viewModel.dump(uri, subdir, EncryptionChoice.None) },
            onPassword = { subdir, pw -> pendingDumpUri = null; viewModel.dump(uri, subdir, EncryptionChoice.Password(pw)) },
            onPasskey = { subdir -> pendingDumpUri = null; viewModel.dumpWithPasskey(activity, uri, subdir) },
            onDismiss = { pendingDumpUri = null },
        )
    }

    // Managed progress sheet for any in-flight operation.
    val managed by viewModel.managed.collectAsState()
    managed?.let { ManagedOperationSheet(it, onClose = viewModel::dismissManaged) }
}

@Composable
private fun EncryptionRow(mode: BackupEncryption, onTurnOn: () -> Unit, onTurnOff: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Encrypt backup", style = MaterialTheme.typography.bodyMedium)
            Text(
                when (mode) {
                    BackupEncryption.PASSWORD -> "Protected with your password."
                    BackupEncryption.PASSKEY -> "Protected with a passkey."
                    BackupEncryption.NONE -> "Off — files are stored unencrypted."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = mode != BackupEncryption.NONE,
            onCheckedChange = { on -> if (on) onTurnOn() else onTurnOff() },
        )
    }
}

@Composable
private fun EncryptionChooserDialog(onPassword: () -> Unit, onPasskey: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encrypt backup") },
        text = {
            Text(
                "Protect the backup with a password you remember, or a passkey (synced by your " +
                    "password manager — nothing to remember, works on your other devices).",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = { TextButton(onClick = onPasskey) { Text("Use passkey") } },
        dismissButton = { TextButton(onClick = onPassword) { Text("Use password") } },
    )
}

@Composable
private fun DumpFlowDialogs(
    uri: Uri,
    looksLikePathline: Boolean,
    checkExists: suspend (String?) -> Boolean,
    onNone: (String?) -> Unit,
    onPassword: (String?, CharArray) -> Unit,
    onPasskey: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var subdir by remember(uri) { mutableStateOf<String?>(null) }
    var subdirChosen by remember(uri) { mutableStateOf(false) }
    var passwordFor by remember(uri) { mutableStateOf(false) }

    when {
        !subdirChosen -> SubdirDialog(
            treeUri = uri,
            defaultLooksLikePathline = looksLikePathline,
            checkExists = checkExists,
            onConfirm = { s -> subdir = s; subdirChosen = true },
            onDismiss = onDismiss,
        )
        passwordFor -> PasswordDialog(
            title = "Password for this dump",
            confirmLabel = "Dump",
            requireConfirm = true,
            onConfirm = { pw -> onPassword(subdir, pw) },
            onDismiss = onDismiss,
        )
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Encrypt this dump?") },
            text = { Text("Choose how to protect this one-time dump.") },
            confirmButton = { TextButton(onClick = { passwordFor = true }) { Text("Password") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onPasskey(subdir) }) { Text("Passkey") }
                    TextButton(onClick = { onNone(subdir) }) { Text("No encryption") }
                }
            },
        )
    }
}

/**
 * Lets the user decide whether the backup lives in a subdirectory of the chosen folder. Shows
 * "<folder> / [ Pathline ] /" with an editable name; clearing it writes to the folder root. Defaults
 * to empty when the chosen folder already looks like a Pathline folder.
 */
@Composable
fun SubdirDialog(
    treeUri: Uri,
    defaultLooksLikePathline: Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
    checkExists: (suspend (String?) -> Boolean)? = null,
) {
    val folderName = treeUri.lastPathSegment?.substringAfterLast('/') ?: "folder"
    var subdir by remember(treeUri) { mutableStateOf(if (defaultLooksLikePathline) "" else "Pathline") }
    var exists by remember(treeUri) { mutableStateOf(false) }

    // Check whether the resolved location already holds a backup, to warn about overwriting.
    LaunchedEffect(subdir, checkExists) {
        exists = checkExists?.invoke(subdir.ifBlank { null }) ?: false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup location") },
        text = {
            Column {
                Text("Files will be written to:", style = MaterialTheme.typography.bodySmall)
                Text(
                    folderName + (if (subdir.isBlank()) " /" else " / $subdir /"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = subdir,
                    onValueChange = { subdir = it },
                    label = { Text("Subfolder (leave empty to use the folder itself)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (exists) {
                    Text(
                        "⚠ This location already contains a Pathline backup. Continuing will " +
                            "overwrite it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(subdir.ifBlank { null }) }) {
                Text(if (exists) "Overwrite" else "Use this")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Password entry. [requireConfirm] adds a second "retype" field (used when *setting* a new password)
 * and gates the confirm button on the two matching. Uses a password keyboard (no autosuggest/learning)
 * and a reveal toggle.
 */
@Composable
fun PasswordDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    requireConfirm: Boolean = false,
) {
    var text by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    val mismatch = requireConfirm && confirm.isNotEmpty() && text != confirm
    val valid = text.length >= 6 && (!requireConfirm || text == confirm)

    val transformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation()
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password)
    val revealToggle: @Composable () -> Unit = {
        IconButton(onClick = { revealed = !revealed }) {
            Icon(
                if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (revealed) "Hide password" else "Show password",
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "This password protects the backup. On this device it's stored securely in the " +
                        "Android Keystore; keep a copy somewhere safe to restore on another device.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = keyboard,
                    trailingIcon = revealToggle,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (requireConfirm) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Retype password") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = transformation,
                        keyboardOptions = keyboard,
                        trailingIcon = revealToggle,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    if (mismatch) {
                        Text(
                            "Passwords don't match",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.toCharArray()) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The "Managed" operation sheet: a modal the user is expected to stay on while a backup/restore/dump
 * runs, with a progress bar and a live log. Not dismissable until the operation finishes.
 */
@Composable
fun ManagedOperationSheet(state: ManagedState, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = { if (state.finished) onClose() },
        properties = DialogProperties(
            dismissOnBackPress = state.finished,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium)

                if (state.progress < 0f) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                }

                val scroll = rememberScrollState()
                LaunchedEffect(state.logs.size) { scroll.animateScrollTo(scroll.maxValue) }
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp).verticalScroll(scroll),
                ) {
                    state.logs.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }

                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose, enabled = state.finished) {
                        Text(if (state.finished) "Close" else "Working…")
                    }
                }
            }
        }
    }
}
