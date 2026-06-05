package net.extrawdw.apps.locationhistory

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.extrawdw.apps.locationhistory.ui.PermissionsStepContent
import net.extrawdw.apps.locationhistory.ui.theme.PathlineTheme

/**
 * Standalone screen the Android permission manager opens from the "i" button next to one of
 * Pathline's permissions (Settings > Apps > Pathline > Permissions). Declared in the manifest as a
 * handler for [Intent.ACTION_VIEW_PERMISSION_USAGE] and guarded by START_VIEW_PERMISSION_USAGE so
 * only the system can launch it.
 *
 * It reuses the second onboarding step's body ([PermissionsStepContent]) to explain why Pathline
 * asks for each permission, but swaps the grant/skip buttons for a single dismiss action since the
 * permissions are managed by the system screen the user came from. The tapped permission group is
 * delivered in [Intent.EXTRA_PERMISSION_GROUP_NAME]; we show the full overview regardless of which
 * one was tapped, so the rationale stays in context.
 */
class PermissionUsageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableTransparentEdgeToEdge()

        // Which permission group the user tapped (e.g. "android.permission-group.LOCATION"); may be
        // null. Kept for context/telemetry — the overview below covers every permission either way.
        @Suppress("UNUSED_VARIABLE")
        val permissionGroup = intent.getStringExtra(Intent.EXTRA_PERMISSION_GROUP_NAME)

        setContent {
            PathlineTheme {
                PermissionUsageScreen(onDone = { finish() })
            }
        }
    }
}

@Composable
private fun PermissionUsageScreen(onDone: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PermissionsStepContent()
            }
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.action_ok))
            }
        }
    }
}
