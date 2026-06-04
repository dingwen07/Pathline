package net.extrawdw.apps.locationhistory.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.backup.BackupOperationController
import net.extrawdw.apps.locationhistory.backup.ManagedKind
import net.extrawdw.apps.locationhistory.backup.ManagedState
import net.extrawdw.apps.locationhistory.data.repo.BackupRepository
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.security.BackupEncryption
import net.extrawdw.apps.locationhistory.security.PasskeyManager
import net.extrawdw.apps.locationhistory.service.RecordingController
import net.extrawdw.apps.locationhistory.work.WorkScheduler
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val recordingController: RecordingController,
    private val workScheduler: WorkScheduler,
    private val backupRepository: BackupRepository,
    private val controller: BackupOperationController,
    private val passkeyManager: PasskeyManager,
) : ViewModel() {

    /** null while loading; true once onboarding has been completed or skipped. */
    val onboardingComplete: StateFlow<Boolean?> = settingsRepository.onboardingComplete
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Managed restore progress, rendered by the shared sheet. */
    val managed: StateFlow<ManagedState?> = controller.state

    val restoreNeedsPassword = MutableStateFlow(false)
    private var pendingRestoreUri: Uri? = null

    init {
        // A successful restore completes onboarding and drops the user into the app.
        viewModelScope.launch {
            controller.state.collect { s ->
                if (s?.kind == ManagedKind.RESTORE && s.finished && s.success) {
                    settingsRepository.setOnboardingComplete(true)
                }
            }
        }
    }

    /**
     * Mark onboarding done. When the recording permissions were granted, turn tracking on so the
     * timeline starts populating right away.
     */
    fun finish(startTracking: Boolean) = viewModelScope.launch {
        if (startTracking) {
            settingsRepository.setTrackingEnabled(true)
            recordingController.startTracking()
            workScheduler.schedulePeriodicTimelineMaintenance()
            workScheduler.schedulePeriodicBackup()
        }
        settingsRepository.setOnboardingComplete(true)
    }

    /** Inspect the chosen folder, then restore — prompting for password / running passkey as needed. */
    fun beginRestore(uri: Uri, activityContext: Context) = viewModelScope.launch {
        val info = backupRepository.cryptoInfoAt(uri)
        if (info == null) {
            controller.fail(ManagedKind.RESTORE, appContext.getString(R.string.action_restore), appContext.getString(R.string.restore_no_backup))
            return@launch
        }
        when (info.mode) {
            BackupEncryption.NONE -> controller.startRestore(uri, null, null)
            BackupEncryption.PASSWORD -> { pendingRestoreUri = uri; restoreNeedsPassword.value = true }
            BackupEncryption.PASSKEY -> {
                val salt = info.prfSalt
                if (salt == null) {
                    controller.fail(ManagedKind.RESTORE, appContext.getString(R.string.action_restore), appContext.getString(R.string.restore_missing_salt))
                    return@launch
                }
                runCatching { passkeyManager.obtainForRestore(activityContext, salt, info.credentialId) }
                    .onSuccess { controller.startRestore(uri, null, it.secret) }
                    .onFailure { controller.fail(ManagedKind.RESTORE, appContext.getString(R.string.passkey_label), it.message ?: appContext.getString(R.string.passkey_unlock_failed)) }
            }
        }
    }

    fun submitRestorePassword(password: CharArray) {
        restoreNeedsPassword.value = false
        pendingRestoreUri?.let { controller.startRestore(it, password, null) }
    }

    fun cancelRestore() {
        restoreNeedsPassword.value = false
        pendingRestoreUri = null
    }

    fun dismissManaged() = controller.dismiss()
}

private enum class OnboardingStep { WELCOME, PERMISSIONS, BACKGROUND }

/**
 * First-run onboarding. Introduces the app, then walks the user through granting foreground
 * permissions (location, activity recognition, notifications) and finally "Allow all the time"
 * background location, which Android requires as a separate step. Each permission step advances
 * automatically once granted; the user can skip the optional background step.
 */
@Composable
fun OnboardingScreen(
    permissions: PathlinePermissions,
    onFinish: (startTracking: Boolean) -> Unit,
    viewModel: OnboardingViewModel,
) {
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }

    val activity = androidx.compose.ui.platform.LocalContext.current
    val restoreNeedsPassword by viewModel.restoreNeedsPassword.collectAsState()
    val managed by viewModel.managed.collectAsState()
    val pickRestoreFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { viewModel.beginRestore(it, activity) } }

    if (restoreNeedsPassword) {
        PasswordDialog(
            title = stringResource(R.string.backup_password_title),
            confirmLabel = stringResource(R.string.action_restore),
            onConfirm = { pw -> viewModel.submitRestorePassword(pw) },
            onDismiss = { viewModel.cancelRestore() },
        )
    }
    managed?.let { ManagedOperationSheet(it, onClose = viewModel::dismissManaged) }

    // Advance automatically as each permission phase is granted.
    LaunchedEffect(permissions.granted) {
        if (step == OnboardingStep.PERMISSIONS && permissions.granted) step = OnboardingStep.BACKGROUND
    }
    LaunchedEffect(permissions.backgroundGranted) {
        if (step == OnboardingStep.BACKGROUND && permissions.backgroundGranted) onFinish(permissions.granted)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            StepDots(step.ordinal, OnboardingStep.entries.size, Modifier.padding(bottom = 8.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (step) {
                    OnboardingStep.WELCOME -> WelcomeContent()
                    OnboardingStep.PERMISSIONS -> PermissionContent(
                        icon = Icons.Filled.LocationOn,
                        title = stringResource(R.string.onboarding_permissions_title),
                        body = stringResource(R.string.onboarding_permissions_body),
                        rows = listOf(
                            PermissionRow(
                                Icons.Filled.Notifications,
                                stringResource(R.string.perm_notifications_title),
                                stringResource(R.string.perm_notifications_desc),
                            ),
                            PermissionRow(
                                Icons.Filled.LocationOn,
                                stringResource(R.string.perm_location_title),
                                stringResource(R.string.perm_location_desc),
                            ),
                            PermissionRow(
                                Icons.AutoMirrored.Filled.DirectionsRun,
                                stringResource(R.string.perm_activity_title),
                                stringResource(R.string.perm_activity_desc),
                            ),
                            PermissionRow(
                                Icons.Filled.Phone,
                                stringResource(R.string.perm_phone_title),
                                stringResource(R.string.perm_phone_desc),
                            ),
                        ),
                    )
                    OnboardingStep.BACKGROUND -> PermissionContent(
                        icon = Icons.Filled.LocationOn,
                        title = stringResource(R.string.onboarding_background_title),
                        body = stringResource(R.string.onboarding_background_body),
                        rows = emptyList(),
                    )
                }
            }

            // Primary / secondary actions per step.
            when (step) {
                OnboardingStep.WELCOME -> {
                    PrimaryButton(stringResource(R.string.action_get_started)) { step = OnboardingStep.PERMISSIONS }
                    TextButton(
                        onClick = { pickRestoreFolder.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_restore_backup)) }
                }
                OnboardingStep.PERMISSIONS -> {
                    PrimaryButton(stringResource(if (permissions.granted) R.string.action_continue else R.string.action_allow_access)) {
                        if (permissions.granted) step = OnboardingStep.BACKGROUND
                        else permissions.requestForeground()
                    }
                    TextButton(
                        onClick = { step = OnboardingStep.BACKGROUND },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_not_now)) }
                }
                OnboardingStep.BACKGROUND -> {
                    PrimaryButton(stringResource(if (permissions.backgroundGranted) R.string.action_finish else R.string.action_allow_all_time)) {
                        if (permissions.backgroundGranted) onFinish(permissions.granted)
                        else permissions.requestBackground()
                    }
                    TextButton(
                        onClick = { onFinish(permissions.granted) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_maybe_later)) }
                }
            }
        }
    }
}

@Composable
private fun WelcomeContent() {
    Icon(
        Icons.Filled.PlayArrow,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.size(20.dp))
    Text(
        stringResource(R.string.onboarding_welcome_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.size(12.dp))
    Text(
        stringResource(R.string.onboarding_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private data class PermissionRow(val icon: ImageVector, val title: String, val description: String)

@Composable
private fun PermissionContent(
    icon: ImageVector,
    title: String,
    body: String,
    rows: List<PermissionRow>,
) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.size(20.dp))
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.size(12.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (rows.isNotEmpty()) {
        Spacer(Modifier.size(24.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            rows.forEach { row ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        row.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            row.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(label)
    }
}

@Composable
private fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(total) { index ->
            val active = index <= current
            Surface(
                shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (index == current) 10.dp else 8.dp),
                content = {},
            )
        }
    }
}
