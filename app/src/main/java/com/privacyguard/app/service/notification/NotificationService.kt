package com.privacyguard.app.service.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Simple notification service for posting system notifications.
 */
class NotificationService(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun blocklistUpdated(sourceName: String, totalEntries: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Blocklist Updated")
            .setContentText("$sourceName: $totalEntries entries loaded")
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID_BLOCKLIST, notification)
    }

    companion object {
        const val CHANNEL_UPDATES         = "pg_updates"
        const val NOTIFICATION_ID_BLOCKLIST = 100
    }
}
