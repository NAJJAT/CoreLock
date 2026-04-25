package com.privacyguard.app.core.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.privacyguard.app.MainActivity
import com.privacyguard.app.domain.model.Notification
import com.privacyguard.app.domain.model.NotificationAction
import com.privacyguard.app.domain.model.NotificationPriority

object NotificationHelper {

    private const val CHANNEL_HIGH = "privacyguard_high"
    private const val CHANNEL_NORMAL = "privacyguard_normal"
    private const val CHANNEL_LOW = "privacyguard_low"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)

        val highChannel = NotificationChannel(
            CHANNEL_HIGH,
            "PrivacyGuard Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Critical alerts about your privacy protection"
            setShowBadge(true)
            enableVibration(true)
        }

        val normalChannel = NotificationChannel(
            CHANNEL_NORMAL,
            "PrivacyGuard Notifications",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Regular notifications about blocked connections"
            setShowBadge(true)
        }

        val lowChannel = NotificationChannel(
            CHANNEL_LOW,
            "PrivacyGuard Updates",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Reports and updates"
            setShowBadge(false)
        }

        manager.createNotificationChannel(highChannel)
        manager.createNotificationChannel(normalChannel)
        manager.createNotificationChannel(lowChannel)
    }

    fun showNotification(context: Context, notification: Notification) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = when (notification.priority) {
            NotificationPriority.CRITICAL,
            NotificationPriority.HIGH -> CHANNEL_HIGH
            NotificationPriority.NORMAL -> CHANNEL_NORMAL
            NotificationPriority.LOW -> CHANNEL_LOW
        }

        val openIntent = PendingIntent.getActivity(
            context,
            notification.id.hashCode(),
            createMainIntent(context, notification.action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(notification.title)
            .setContentText(notification.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.message))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(toAndroidPriority(notification.priority))

        if (notification.action is NotificationAction.BlockApp) {
            val blockIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("block_app", notification.action.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val blockPendingIntent = PendingIntent.getActivity(
                context,
                notification.id.hashCode() + 1,
                blockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_delete, "Block App", blockPendingIntent)
        }

        val dismissIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
            putExtra("notification_id", notification.id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notification.id.hashCode() + 2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)

        manager.notify(notification.id.hashCode(), builder.build())
    }

    private fun createMainIntent(context: Context, action: NotificationAction?): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            when (action) {
                is NotificationAction.OpenApp -> putExtra("open_screen", action.screen)
                NotificationAction.ViewConnections -> putExtra("open_screen", "connections")
                NotificationAction.ViewStatistics -> putExtra("open_screen", "statistics")
                is NotificationAction.ViewConnection -> {
                    putExtra("open_screen", "connections")
                    putExtra("connection_id", action.connectionId)
                }
                else -> Unit
            }
        }
    }

    private fun toAndroidPriority(priority: NotificationPriority): Int {
        return when (priority) {
            NotificationPriority.CRITICAL,
            NotificationPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
            NotificationPriority.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
            NotificationPriority.LOW -> NotificationCompat.PRIORITY_LOW
        }
    }
}

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getStringExtra("notification_id") ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(notificationId.hashCode())
    }
}
