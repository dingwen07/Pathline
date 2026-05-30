package net.extrawdw.apps.locationhistory.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import net.extrawdw.apps.locationhistory.MainActivity
import net.extrawdw.apps.locationhistory.R
import net.extrawdw.apps.locationhistory.core.DevicePhysicalState

/** Notification channel + builder for the foreground recording session. */
object Notifications {
    const val RECORDING_CHANNEL_ID = "pathline_recording"
    const val RECORDING_NOTIFICATION_ID = 7001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(RECORDING_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                RECORDING_CHANNEL_ID,
                context.getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.recording_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildRecordingNotification(context: Context, state: DevicePhysicalState): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, RECORDING_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.recording_title))
            .setContentText(state.label)
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
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }
}
