package net.extrawdw.apps.locationhistory

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds can't pass Play Integrity (not installed from Play), so they use the debug provider.
 * On first run it logs a debug token; register that token in the Firebase console
 * (App Check -> Apps -> Manage debug tokens) to treat this build as trusted.
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory =
    DebugAppCheckProviderFactory.getInstance()
