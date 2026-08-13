package net.extrawdw.apps.locationhistory.service

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import net.extrawdw.apps.locationhistory.core.AppLog

/**
 * Master switch for Firebase telemetry: Crashlytics (crash reporting) + Performance Monitoring.
 *
 * The user's choice lives in settings (opt-out, default on) and is pushed here at app startup and
 * whenever the Settings switch is toggled. Both SDKs persist the last applied value and honor it at
 * the next cold start -- before [android.app.Application.onCreate] runs -- so an opted-out state
 * survives process death with no startup collection window.
 *
 * Firebase is scoped to telemetry only. Maps Platform credentials are protected independently with
 * Android package/certificate and API restrictions.
 */
object FirebaseTelemetry {

    /** Apply [enabled] to both telemetry SDKs. No-op (logged) when Firebase isn't configured. */
    fun apply(enabled: Boolean) {
        runCatching {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
            FirebasePerformance.getInstance().setPerformanceCollectionEnabled(enabled)
            // Opting out should also drop any crash reports already cached on disk, so a later run
            // doesn't upload them.
            if (!enabled) FirebaseCrashlytics.getInstance().deleteUnsentReports()
            AppLog.i("App", "Firebase telemetry collection enabled=$enabled")
        }.onFailure { AppLog.w("App", "Firebase telemetry apply failed: ${it.message}") }
    }
}
