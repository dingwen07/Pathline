package net.extrawdw.apps.locationhistory

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Edge-to-edge with transparent system bars in both navigation modes.
 *
 * Plain [enableEdgeToEdge] defaults the navigation bar to `SystemBarStyle.auto(lightScrim,
 * darkScrim)` -- opaque scrims -- and Android 15 also flips `isNavigationBarContrastEnforced` on by
 * default. Neither shows in gesture nav (the bar is a transparent pill) but both paint a bar behind
 * the buttons in 3-button nav, which reads as "edge-to-edge only works with gestures". Forcing
 * transparent scrims here plus opting out of contrast enforcement makes button mode match gesture
 * mode. Our screens already keep content clear of the bars via navigationBars / safeDrawing insets.
 */
fun ComponentActivity.enableTransparentEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    window.isNavigationBarContrastEnforced = false
}
