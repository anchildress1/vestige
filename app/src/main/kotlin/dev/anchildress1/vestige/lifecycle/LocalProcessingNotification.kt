package dev.anchildress1.vestige.lifecycle

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dev.anchildress1.vestige.MainActivity
import dev.anchildress1.vestige.R

/** Notification channel + builder for the conditional foreground service. */
internal object LocalProcessingNotification {

    const val CHANNEL_ID: String = "vestige.local_processing"
    const val IMPORTANCE: Int = NotificationManager.IMPORTANCE_LOW
    const val NOTIFICATION_ID: Int = 1

    internal fun buildChannel(context: Context): NotificationChannel = NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.notification_channel_local_processing_name),
        IMPORTANCE,
    ).apply {
        description = context.getString(R.string.notification_channel_local_processing_description)
        setShowBadge(false)
    }

    fun registerChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(buildChannel(context))
    }

    fun build(context: Context): android.app.Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notification_local_processing_text))
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(buildTapIntent(context))
        .build()

    private fun buildTapIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
