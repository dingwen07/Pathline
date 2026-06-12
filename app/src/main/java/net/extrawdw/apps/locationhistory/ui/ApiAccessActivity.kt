package net.extrawdw.apps.locationhistory.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import net.extrawdw.apps.locationhistory.enableTransparentEdgeToEdge
import net.extrawdw.apps.locationhistory.ui.theme.PathlineTheme

/**
 * Hosts the "Access to Pathline data" screen as its own activity so it uses the platform's default
 * window open/close transition and gets predictive back for free — no hand-rolled animation. Back
 * (button or gesture) finishes the activity and returns to wherever it was launched from (Settings).
 */
@AndroidEntryPoint
class ApiAccessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableTransparentEdgeToEdge()
        // Per-app grant management is consent UI: keep other apps' overlay windows from drawing on
        // top of it (tapjacking defense).
        window.setHideOverlayWindows(true)
        setContent {
            PathlineTheme {
                ApiAccessScreen(onBack = { finish() })
            }
        }
    }
}
