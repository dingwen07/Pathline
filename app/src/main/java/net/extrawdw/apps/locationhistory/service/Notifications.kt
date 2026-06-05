package net.extrawdw.apps.locationhistory.service

import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import net.extrawdw.apps.locationhistory.MainActivity
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState

/** Notification channel + builder for the foreground recording session. */
object Notifications {
    const val RECORDING_CHANNEL_ID = "pathline_recording"
    const val RECORDING_NOTIFICATION_ID = 7001

    /**
     * A separate, higher-importance channel for one-off alerts (e.g. recording was turned off when
     * the app was swiped from Recents). It is IMPORTANCE_HIGH so it pops up as a heads-up, but with
     * sound and vibration removed — visible but quiet, deliberately *not* a silent (LOW) channel so
     * the user actually sees it. Kept distinct from the ongoing recording channel.
     */
    const val ALERT_CHANNEL_ID = "pathline_alerts"
    const val RECORDING_STOPPED_NOTIFICATION_ID = 7002

    /**
     * A context whose resources resolve against the app's per-app language (the Android 13+
     * "App language" preference). These notifications are posted from [LocationRecorderService] and
     * the task-removal path; a Service context does **not** pick up the per-app locale the way an
     * Activity does, so plain getString() would fall back to the system locale and render in the
     * wrong language. Re-resolve strings against the locale chosen in [LocaleManager].
     */
    private fun localized(context: Context): Context {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        if (locales == null || locales.isEmpty) return context
        val config = Configuration(context.resources.configuration).apply { setLocales(locales) }
        return context.createConfigurationContext(config)
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(RECORDING_CHANNEL_ID) == null) {
            val res = localized(context)
            val channel = NotificationChannel(
                RECORDING_CHANNEL_ID,
                res.getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = res.getString(R.string.recording_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun ensureAlertChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            val res = localized(context)
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                res.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = res.getString(R.string.alert_channel_desc)
                // Heads-up popup, but no sound or vibration.
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** Alert (popup, silent) telling the user recording was turned off after task removal. */
    fun notifyRecordingStopped(context: Context) {
        ensureAlertChannel(context)
        val res = localized(context)
        val text = res.getString(R.string.recording_stopped_text)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle(res.getString(R.string.recording_stopped_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context))
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Reinforce the channel's heads-up-but-silent intent (no default sound/vibration).
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(0)
            .setVibrate(longArrayOf(0L))
            .setAutoCancel(true)
            .addAction(
                0,
                res.getString(R.string.recording_stopped_action_resume),
                actionIntent(context, RecordingActionReceiver.ACTION_RESUME_RECORDING, 1),
            )
            .addAction(
                0,
                res.getString(R.string.recording_stopped_action_keep_on),
                actionIntent(context, RecordingActionReceiver.ACTION_KEEP_RECORDING_ON_CLOSE, 2),
            )
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(RECORDING_STOPPED_NOTIFICATION_ID, notification)
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, RecordingActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun buildRecordingNotification(context: Context, state: DevicePhysicalState): Notification {
        ensureChannel(context)
        val res = localized(context)
        return NotificationCompat.Builder(context, RECORDING_CHANNEL_ID)
            .setContentTitle(res.getString(R.string.recording_title))
            .setContentText(res.getString(state.labelRes))
            .setContentIntent(contentIntent(context))
            // Status bar: simplified monochrome pin. Shade large icon: current movement state.
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setLargeIcon(stateBadge(context, state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @DrawableRes
    private fun largeIconFor(state: DevicePhysicalState): Int = when (state) {
        DevicePhysicalState.STATIONARY -> R.drawable.ic_state_stationary
        DevicePhysicalState.WALKING -> R.drawable.ic_state_walking
        DevicePhysicalState.RUNNING -> R.drawable.ic_state_running
        DevicePhysicalState.CYCLING -> R.drawable.ic_state_cycling
        DevicePhysicalState.IN_VEHICLE -> R.drawable.ic_state_vehicle
        DevicePhysicalState.UNKNOWN -> R.drawable.ic_state_unknown
    }

    /** Renders the state badge drawable to a bitmap for use as the notification's large icon. */
    private fun stateBadge(context: Context, state: DevicePhysicalState): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, largeIconFor(state)) ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 108
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 108
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }
}
