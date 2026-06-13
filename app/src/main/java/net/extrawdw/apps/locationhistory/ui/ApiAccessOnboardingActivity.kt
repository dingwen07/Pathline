package net.extrawdw.apps.locationhistory.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.api.PathlineContract
import net.extrawdw.apps.locationhistory.data.repo.ApiScope
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.enableTransparentEdgeToEdge
import net.extrawdw.apps.locationhistory.ui.theme.PathlineTheme
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject

/**
 * Full-screen onboarding for the third-party data API. It explains what the API is and what an app can
 * be granted, then lets the user turn access on (or decline). Three entry points, [EXTRA_MODE]:
 *
 * - [MODE_MANAGE] — the Settings "Access to Pathline data" row tap. Once the user has decided (access
 *   on, or "don't ask again"), it skips the explainer and opens the access manager; a choice here also
 *   continues into the manager. This is the only mode that offers "don't ask again".
 * - [MODE_ENABLE] — the Settings switch turning on. Always shows the explainer; a choice returns to
 *   Settings.
 * - [MODE_REQUEST] — another app fired [PathlineContract.Actions.REQUEST_API_ACCESS]. Shows the
 *   explainer with the **requesting app's name and icon** and a note that this is the overall switch.
 *   It **returns a result to that app** (`RESULT_OK`/`RESULT_CANCELED` + [PathlineContract.Actions.EXTRA_ACCESS_ENABLED])
 *   and never opens the manager. If access is already on it returns `RESULT_OK` immediately. The
 *   requester's identity comes only from [getCallingPackage] (system-verified, set for
 *   startActivityForResult); a caller that can't be verified gets a generic "couldn't identify the
 *   app" screen with no consent buttons instead of a personalized sheet.
 *
 * There is intentionally no up/back affordance; the system back button/gesture still dismisses it
 * (which, for a request, returns `RESULT_CANCELED` to the caller).
 */
@AndroidEntryPoint
class ApiAccessOnboardingActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge enabled up front (best practice), before any work that might short-circuit to a
        // result. The screen keeps content clear of the system bars with safeDrawingPadding().
        enableTransparentEdgeToEdge()
        // Consent UI: keep other apps' overlay windows from drawing on top of it (tapjacking defense).
        window.setHideOverlayWindows(true)

        val mode = when {
            intent?.action == PathlineContract.Actions.REQUEST_API_ACCESS -> MODE_REQUEST
            else -> intent?.getIntExtra(EXTRA_MODE, MODE_MANAGE) ?: MODE_MANAGE
        }
        // A request defaults to "declined" until the user turns it on, so a back-dismiss returns CANCELED.
        if (mode == MODE_REQUEST) setResultForRequest(enabled = false)

        // The routing decision needs the persisted settings, and a cold DataStore read hits disk —
        // never block the main thread on it. Until it resolves the window simply shows the theme
        // background; the explainer (or the short-circuit) follows in the same frame the read lands.
        lifecycleScope.launch {
            val state = settings.settings.first()
            when (mode) {
                MODE_MANAGE -> if (state.apiAccessEnabled || state.apiAccessConsentNeverAsk) {
                    openManager(); finish(); return@launch
                }

                MODE_ENABLE -> if (state.apiAccessEnabled) {
                    finish(); return@launch
                }

                MODE_REQUEST -> when {
                    // Already on -> nothing to ask; return success without any UI.
                    state.apiAccessEnabled -> {
                        setResultForRequest(enabled = true); finish(); return@launch
                    }
                    // The user chose "don't ask again" -> never prompt for a request; decline silently.
                    state.apiAccessConsentNeverAsk -> {
                        setResultForRequest(enabled = false); finish(); return@launch
                    }
                }
            }
            showExplainer(mode)
        }
    }

    private fun showExplainer(mode: Int) {
        val requester = if (mode == MODE_REQUEST) resolveRequester() else null
        // A request whose caller can't be verified must not render a personalized consent sheet:
        // show a generic "couldn't identify the app" screen and leave the RESULT_CANCELED default.
        if (mode == MODE_REQUEST && requester == null) {
            setContent {
                PathlineTheme { UnverifiedRequestScreen(onClose = { finish() }) }
            }
            return
        }
        setContent {
            PathlineTheme {
                ApiAccessOnboardingScreen(
                    requester = requester,
                    // The switch is an explicit "turn it on" gesture, so "don't ask again" doesn't fit
                    // there; the row tap and third-party requests still offer it.
                    showNeverAsk = mode != MODE_ENABLE,
                    onTurnOn = {
                        decide(mode, enabledAfter = true) {
                            settings.setApiAccessEnabled(
                                true
                            )
                        }
                    },
                    onNotNow = { decide(mode, enabledAfter = false) { /* leave off */ } },
                    onNeverAsk = {
                        decide(
                            mode,
                            enabledAfter = false
                        ) { settings.setApiAccessConsentNeverAsk(true) }
                    },
                )
            }
        }
    }

    /** Persist the choice, then route per entry point: manager (manage), back to Settings (enable), or
     *  a result to the requesting app (request). */
    private fun decide(mode: Int, enabledAfter: Boolean, apply: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { apply() }
            when (mode) {
                MODE_MANAGE -> openManager()
                MODE_REQUEST -> setResultForRequest(enabledAfter)
                else -> Unit // MODE_ENABLE just returns to Settings
            }
            finish()
        }
    }

    private fun setResultForRequest(enabled: Boolean) {
        setResult(
            if (enabled) RESULT_OK else RESULT_CANCELED,
            Intent().putExtra(PathlineContract.Actions.EXTRA_ACCESS_ENABLED, enabled),
        )
    }

    /**
     * The app that launched a [MODE_REQUEST], for the header. Identity comes ONLY from
     * [getCallingPackage], which the system fills in (and verifies) when the caller used
     * `startActivityForResult`. [getReferrer] is deliberately NOT consulted: it returns the
     * caller-supplied `Intent.EXTRA_REFERRER` when present, so any app could impersonate a trusted
     * one on this consent sheet. A plain-startActivity caller resolves to null and gets the
     * unverified screen. If the label or icon can't be resolved, the raw package name is still
     * shown so the request always looks distinct.
     */
    private fun resolveRequester(): RequesterInfo? {
        val pkg = callingPackage?.takeIf { it != packageName } ?: return null
        val pm = packageManager
        val pkgInfo =
            runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()
        val appInfo = pkgInfo?.applicationInfo
        val label =
            appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                ?: pkg
        val icon = appInfo?.let {
            runCatching {
                pm.getApplicationIcon(it).toBitmap().asImageBitmap()
            }.getOrNull()
        }
        // The Pathline scopes this app declares with <uses-permission> — what it's asking to be able to
        // read (same set the "Access to Pathline data" manager shows per app).
        val declared = pkgInfo?.requestedPermissions?.toSet().orEmpty()
        val scopes = ApiScope.entries.filter { it.permission in declared }
        return RequesterInfo(label, icon, scopes)
    }

    private fun openManager() {
        startActivity(Intent(this, ApiAccessActivity::class.java))
    }

    companion object {
        const val EXTRA_MODE = "mode"

        /** Entry from the Settings row: continues into the access manager. */
        const val MODE_MANAGE = 0

        /** Entry from the Settings switch: explainer only, returns to Settings. */
        const val MODE_ENABLE = 1

        /** Entry from a third-party [PathlineContract.Actions.REQUEST_API_ACCESS]: returns a result. */
        const val MODE_REQUEST = 2

        fun manageIntent(context: Context): Intent =
            Intent(context, ApiAccessOnboardingActivity::class.java).putExtra(
                EXTRA_MODE,
                MODE_MANAGE
            )

        fun enableIntent(context: Context): Intent =
            Intent(context, ApiAccessOnboardingActivity::class.java).putExtra(
                EXTRA_MODE,
                MODE_ENABLE
            )
    }
}

/** The app behind a [ApiAccessOnboardingActivity.MODE_REQUEST] — its label, icon, and declared scopes. */
private data class RequesterInfo(
    val label: String,
    val icon: ImageBitmap?,
    val declaredScopes: List<ApiScope>,
)

private data class ApiPermInfo(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
    @param:StringRes val desc: Int,
)

/**
 * The data an app can be granted, shown with friendly labels (not raw permission names). One row per
 * dangerous permission, except the Search and Edit-annotations groups, which collapse to a single row
 * per group (matching how the OS bundles a group into one grant at request time). The LOCATION_HISTORY
 * group is intentionally kept as its two member rows — precise routes vs raw location samples are
 * distinct enough to spell out. Kept in sync with the <permission> / <permission-group> declarations in
 * AndroidManifest.xml:
 *   - READ_TIMELINE (ungrouped)
 *   - READ_TIMELINE_ROUTES (LOCATION_HISTORY group, shown individually)
 *   - READ_LOCATION_HISTORY (LOCATION_HISTORY group, shown individually)
 *   - READ_EXTENDED_HISTORY (ungrouped)
 *   - Search group <- READ_ALL_PLACES + READ_ANNOTATIONS + SEARCH_DATA
 *   - Edit-annotations group <- WRITE_ANNOTATIONS + WRITE_ANNOTATIONS_NOTES
 */
private val API_PERMISSIONS = listOf(
    ApiPermInfo(
        R.drawable.ic_perm_timeline,
        R.string.perm_read_timeline_label,
        R.string.perm_read_timeline_description
    ),
    ApiPermInfo(
        R.drawable.ic_perm_route,
        R.string.perm_read_timeline_route_label,
        R.string.perm_read_timeline_route_description
    ),
    ApiPermInfo(
        R.drawable.ic_perm_location_history,
        R.string.perm_read_location_history_label,
        R.string.perm_read_location_history_description
    ),
    ApiPermInfo(
        R.drawable.ic_perm_extended_history,
        R.string.perm_read_extended_history_label,
        R.string.perm_read_extended_history_description
    ),
    ApiPermInfo(
        R.drawable.ic_perm_search,
        R.string.perm_group_search_label,
        R.string.perm_group_search_description
    ),
    ApiPermInfo(
        R.drawable.ic_perm_write,
        R.string.perm_group_write_annotations_label,
        R.string.perm_group_write_annotations_description
    ),
)

@Composable
private fun ApiAccessOnboardingScreen(
    requester: RequesterInfo?,
    showNeverAsk: Boolean,
    onTurnOn: () -> Unit,
    onNotNow: () -> Unit,
    onNeverAsk: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(start = 28.dp, end = 28.dp, top = 8.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Padlock + title stay at the top for both the in-app and the third-party entry.
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(20.dp))
                Text(
                    stringResource(R.string.api_access_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.api_consent_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(24.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    API_PERMISSIONS.forEach { perm -> ApiPermissionRow(perm) }
                }
                // The third-party "who's asking" card is the last scrollable item.
                if (requester != null) {
                    Spacer(Modifier.size(24.dp))
                    RequesterCard(requester)
                }
            }

            // Footer sticks just above the buttons (outside the scroll) so it's always visible.
            Spacer(Modifier.size(16.dp))
            Text(
                stringResource(R.string.api_consent_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onTurnOn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) { Text(stringResource(R.string.api_consent_turn_on)) }
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.api_consent_not_now))
            }
            if (showNeverAsk) {
                TextButton(onClick = onNeverAsk, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.api_consent_never_ask))
                }
            }
        }
    }
}

/**
 * Shown for a [ApiAccessOnboardingActivity.MODE_REQUEST] whose caller could not be verified
 * ([android.app.Activity.getCallingPackage] is null because the app used plain `startActivity`).
 * Renders NO third-party name, icon or scopes and offers no consent buttons — the request stays
 * declined (RESULT_CANCELED) and the user is pointed at Settings to enable access manually.
 */
@Composable
private fun UnverifiedRequestScreen(onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(start = 28.dp, end = 28.dp, top = 8.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(20.dp))
                Text(
                    stringResource(R.string.api_consent_unverified_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.api_consent_unverified_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) { Text(stringResource(R.string.action_ok)) }
        }
    }
}

/**
 * Card for a third-party request: the requesting app's icon + name, the Pathline scopes it declares
 * (the same set the access manager shows), and a note that the user may still need to approve them.
 * The padlock + title at the top are the same in both entry points.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequesterCard(requester: RequesterInfo) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (requester.icon != null) {
                Image(
                    bitmap = requester.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                )
            } else {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    stringResource(R.string.api_consent_request_header, requester.label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (requester.declaredScopes.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy((-8).dp)
                    ) {
                        requester.declaredScopes.forEach { scope ->
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = {
                                    Text(
                                        stringResource(scope.labelRes),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.api_consent_request_note, requester.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ApiPermissionRow(perm: ApiPermInfo) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(perm.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(perm.label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(perm.desc).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
