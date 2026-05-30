package net.extrawdw.apps.locationhistory

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "onCreate")
        Notifications.ensureChannel(this)
    }
}
