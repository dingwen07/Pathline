package net.extrawdw.apps.locationhistory

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import dagger.hilt.android.HiltAndroidApp
import net.extrawdw.apps.locationhistory.core.AppLog
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "onCreate")
        Notifications.ensureChannel(this)
        initAppCheck()
    }

    /**
     * Install Firebase App Check so the Routes web-service call can attest a genuine Pathline
     * install. Requires google-services.json; when it's absent (CI / fresh checkout)
     * [FirebaseApp.initializeApp] returns null and we skip it -- the app still runs, routing just
     * won't carry an App Check token. [appCheckProviderFactory] is variant-specific (Play Integrity
     * in release, debug provider locally).
     */
    private fun initAppCheck() {
        runCatching {
            if (FirebaseApp.initializeApp(this) == null) {
                AppLog.w("App", "Firebase not configured; App Check disabled")
                return
            }
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(appCheckProviderFactory())
            AppLog.i("App", "App Check installed")
        }.onFailure { AppLog.w("App", "App Check init failed: ${it.message}") }
    }
}
