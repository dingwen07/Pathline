package net.extrawdw.apps.locationhistory.ui

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import net.extrawdw.apps.locationhistory.R
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
import androidx.compose.ui.res.stringResource
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
    @ApplicationContext private val appContext: Context,
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

    /** Fresh setup: persist the destination with the chosen encryption applied to the first backup. */
    fun configure(uri: Uri, subdir: String?, choice: EncryptionChoice) {
        controller.startConfigure(uri, subdir, choice)
        workScheduler.schedulePeriodicBackup()
    }

    fun configureWithPasskey(activityContext: Context, uri: Uri, subdir: String?) = viewModelScope.launch {
        runCatching { passkeyManager.obtainForSetup(activityContext) }
            .onSuccess {
                controller.startConfigure(uri, subdir, EncryptionChoice.Passkey(it.secret, it.salt, it.credentialId))
                workScheduler.schedulePeriodicBackup()
            }
            .onFailure { controller.fail(ManagedKind.BACKUP, appContext.getString(R.string.passkey_label), it.message ?: appContext.getString(R.string.passkey_setup_failed)) }
    }

    /** Change destination / reconnect an existing backup, keeping its current encryption. */
    fun reconfigure(uri: Uri, subdir: String?) {
        controller.startConfigure(uri, subdir, null)
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
            .onFailure { controller.fail(ManagedKind.BACKUP, appContext.getString(R.string.passkey_label), it.message ?: appContext.getString(R.string.passkey_setup_failed)) }
    }

    fun dump(uri: Uri, subdir: String?, choice: EncryptionChoice) = controller.startDump(uri, subdir, choice)

    fun dumpWithPasskey(activityContext: Context, uri: Uri, subdir: String?) = viewModelScope.launch {
        runCatching { passkeyManager.obtainForSetup(activityContext) }
            .onSuccess { controller.startDump(uri, subdir, EncryptionChoice.Passkey(it.secret, it.salt, it.credentialId)) }
            .onFailure { controller.fail(ManagedKind.DUMP, appContext.getString(R.string.passkey_label), it.message ?: appContext.getString(R.string.passkey_setup_failed)) }
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
            Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
            val cfg = config

            if (cfg?.treeUri == null) {
                Text(
                    stringResource(R.string.backup_intro),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { pickBackupFolder.launch(null) }) { Text(stringResource(R.string.backup_choose_folder)) }
            } else {
                val selectedFolderLabel = stringResource(R.string.backup_selected_folder)
                val dest = buildString {
                    append(Uri.parse(cfg.treeUri).lastPathSegment ?: selectedFolderLabel)
                    if (!cfg.subdir.isNullOrBlank()) append(" / ${cfg.subdir}")
                }
                Text(stringResource(R.string.backup_dest, dest), style = MaterialTheme.typography.bodySmall)
                if (cfg.lastBackupMs > 0) {
                    Text(
                        stringResource(R.string.backup_last, DateFormat.getDateTimeInstance().format(Date(cfg.lastBackupMs))),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::backupNow) { Text(stringResource(R.string.backup_now)) }
                    OutlinedButton(onClick = { pickBackupFolder.launch(null) }) { Text(stringResource(R.string.backup_change_reconnect)) }
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
                        Text(stringResource(R.string.backup_gpx_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.backup_gpx_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = cfg.gpxEnabled, onCheckedChange = viewModel::setGpxEnabled)
                }
                if (cfg.gpxEnabled) {
                    OutlinedButton(onClick = { pickGpxFolder.launch(null) }) {
                        Text(stringResource(if (cfg.gpxTreeUri == null) R.string.backup_gpx_use_separate else R.string.backup_gpx_change))
                    }
                }
            }

            Text(
                stringResource(R.string.backup_dump_intro),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = { pickDumpFolder.launch(null) }) { Text(stringResource(R.string.backup_dump_action)) }
        }
    }

    pendingBackupUri?.let { uri ->
        if (config?.treeUri == null) {
            // Fresh setup: pick a subdir, then choose encryption for the very first backup.
            EncryptionSetupFlow(
                uri = uri,
                looksLikePathline = viewModel.folderLooksLikePathline(uri),
                checkExists = { sub -> viewModel.backupExists(uri, sub) },
                encryptTitle = stringResource(R.string.encrypt_title),
                encryptText = stringResource(R.string.encrypt_chooser_text),
                passwordTitle = stringResource(R.string.backup_set_password_title),
                passwordConfirmLabel = stringResource(R.string.action_encrypt),
                onNone = { subdir -> pendingBackupUri = null; viewModel.configure(uri, subdir, EncryptionChoice.None) },
                onPassword = { subdir, pw -> pendingBackupUri = null; viewModel.configure(uri, subdir, EncryptionChoice.Password(pw)) },
                onPasskey = { subdir -> pendingBackupUri = null; viewModel.configureWithPasskey(activity, uri, subdir) },
                onDismiss = { pendingBackupUri = null },
            )
        } else {
            // Changing destination / reconnecting: keep the existing encryption, just confirm subdir.
            SubdirDialog(
                treeUri = uri,
                defaultLooksLikePathline = viewModel.folderLooksLikePathline(uri),
                checkExists = { sub -> viewModel.backupExists(uri, sub) },
                onConfirm = { subdir -> pendingBackupUri = null; viewModel.reconfigure(uri, subdir) },
                onDismiss = { pendingBackupUri = null },
            )
        }
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
            title = stringResource(R.string.backup_set_password_title),
            confirmLabel = stringResource(R.string.action_encrypt),
            requireConfirm = true,
            onConfirm = { pw -> showPasswordDialog = false; viewModel.enablePassword(pw) },
            onDismiss = { showPasswordDialog = false },
        )
    }

    // Dump flow: subdir → encryption choice.
    pendingDumpUri?.let { uri ->
        EncryptionSetupFlow(
            uri = uri,
            looksLikePathline = viewModel.folderLooksLikePathline(uri),
            checkExists = { sub -> viewModel.backupExists(uri, sub) },
            encryptTitle = stringResource(R.string.dump_encrypt_title),
            encryptText = stringResource(R.string.dump_encrypt_text),
            passwordTitle = stringResource(R.string.dump_password_title),
            passwordConfirmLabel = stringResource(R.string.action_dump),
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
            Text(stringResource(R.string.encrypt_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    when (mode) {
                        BackupEncryption.PASSWORD -> R.string.encrypt_password_on
                        BackupEncryption.PASSKEY -> R.string.encrypt_passkey_on
                        BackupEncryption.NONE -> R.string.encrypt_off
                    },
                ),
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
        title = { Text(stringResource(R.string.encrypt_title)) },
        text = {
            Text(
                stringResource(R.string.encrypt_chooser_text),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = { TextButton(onClick = onPasskey) { Text(stringResource(R.string.action_use_passkey)) } },
        dismissButton = { TextButton(onClick = onPassword) { Text(stringResource(R.string.action_use_password)) } },
    )
}

/**
 * Shared setup flow for both the recurring backup and the one-time dump: pick a subdirectory, then
 * choose protection (password / passkey / none). The caller supplies its own copy for the encryption
 * prompt and password dialog so the wording fits the context. [onPasskey] hands back the chosen
 * subdir; the caller runs the passkey ceremony (it needs an Activity).
 */
@Composable
private fun EncryptionSetupFlow(
    uri: Uri,
    looksLikePathline: Boolean,
    checkExists: suspend (String?) -> Boolean,
    encryptTitle: String,
    encryptText: String,
    passwordTitle: String,
    passwordConfirmLabel: String,
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
            title = passwordTitle,
            confirmLabel = passwordConfirmLabel,
            requireConfirm = true,
            onConfirm = { pw -> onPassword(subdir, pw) },
            onDismiss = onDismiss,
        )
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(encryptTitle) },
            text = { Text(encryptText) },
            confirmButton = { TextButton(onClick = { passwordFor = true }) { Text(stringResource(R.string.action_password)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onPasskey(subdir) }) { Text(stringResource(R.string.action_passkey)) }
                    TextButton(onClick = { onNone(subdir) }) { Text(stringResource(R.string.action_no_encryption)) }
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
    val folderName = treeUri.lastPathSegment?.substringAfterLast('/') ?: stringResource(R.string.backup_folder_fallback)
    val defaultSubdir = stringResource(R.string.backup_default_subdir)
    var subdir by remember(treeUri) { mutableStateOf(if (defaultLooksLikePathline) "" else defaultSubdir) }
    var exists by remember(treeUri) { mutableStateOf(false) }

    // Check whether the resolved location already holds a backup, to warn about overwriting.
    LaunchedEffect(subdir, checkExists) {
        exists = checkExists?.invoke(subdir.ifBlank { null }) ?: false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_location_title)) },
        text = {
            Column {
                Text(stringResource(R.string.backup_files_written), style = MaterialTheme.typography.bodySmall)
                Text(
                    folderName + (if (subdir.isBlank()) " /" else " / $subdir /"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = subdir,
                    onValueChange = { subdir = it },
                    label = { Text(stringResource(R.string.backup_subfolder_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (exists) {
                    Text(
                        stringResource(R.string.backup_overwrite_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(subdir.ifBlank { null }) }) {
                Text(stringResource(if (exists) R.string.action_overwrite else R.string.action_use_this))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
                contentDescription = stringResource(if (revealed) R.string.cd_hide_password else R.string.cd_show_password),
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    stringResource(R.string.password_dialog_info),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.field_password)) },
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
                        label = { Text(stringResource(R.string.field_retype_password)) },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = transformation,
                        keyboardOptions = keyboard,
                        trailingIcon = revealToggle,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    if (mismatch) {
                        Text(
                            stringResource(R.string.password_mismatch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.toCharArray()) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
                        Text(stringResource(if (state.finished) R.string.managed_close else R.string.managed_working))
                    }
                }
            }
        }
    }
}
