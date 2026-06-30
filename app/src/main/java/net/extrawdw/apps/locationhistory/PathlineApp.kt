package net.extrawdw.apps.locationhistory

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.extrawdw.apps.locationhistory.core.AppLog
import net.extrawdw.apps.locationhistory.data.repo.SettingsRepository
import net.extrawdw.apps.locationhistory.service.FirebaseTelemetry
import net.extrawdw.apps.locationhistory.service.Notifications
import javax.inject.Inject

/**
 * Application entry point. Wires Hilt, provides the WorkManager configuration backed by Hilt's
 * worker factory (so workers can inject repositories), and prepares the recording notification
 * channel.
 */
@HiltAndroidApp
class PathlineApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** Long-lived scope for fire-and-forget app-init work (e.g. reconciling the telemetry switch). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.logRecentApplicationExitInfo(this)
        AppLog.i("App", "onCreate")
        Notifications.ensureChannel(this)
        initFirebase()
    }

    /**
     * Configure Firebase services when google-services.json is present. Without it (CI / fresh
     * checkout), [FirebaseApp.initializeApp] returns null and the app still runs; routes simply won't
     * carry an App Check token and telemetry stays off. Crashlytics / Performance collection follows
     * the in-app "Share crash & performance reports" switch via [FirebaseTelemetry]; here we reconcile
     * the stored preference into the SDKs. Reading it asynchronously is safe -- each SDK persists its
     * own last value and honors it at the next cold start, so there's no startup collection window.
     */
    private fun initFirebase() {
        runCatching {
            if (FirebaseApp.initializeApp(this) == null) {
                AppLog.w("App", "Firebase not configured; Firebase services disabled")
                return
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(appCheckProviderFactory())
            appScope.launch { FirebaseTelemetry.apply(settingsRepository.telemetryEnabled()) }
            AppLog.i("App", "Firebase services configured")
        }.onFailure { AppLog.w("App", "Firebase init failed: ${it.message}") }
    }
}
