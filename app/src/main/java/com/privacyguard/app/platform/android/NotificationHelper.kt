package com.privacyguard.platform.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Centralized helper for creating and posting all notifications used by PrivacyGuard.
 *
 * Channels:
 *  - [CHANNEL_VPN_STATUS]   — persistent foreground notification showing VPN state.
 *  - [CHANNEL_ALERTS]       — high-priority alerts for suspicious connections.
 *  - [CHANNEL_WEEKLY_REPORT]— low-priority weekly privacy summary.
 *
 * All channel creation is idempotent; safe to call multiple times.
 */
class NotificationHelper(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

    // ─────────────────────────────────────────────────────────────────────────
    // Channel Registration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates all notification channels. Must be called before posting any
     * notification. Safe to call on every app start — Android ignores duplicate
     * channel creation.
     */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channels = listOf(
            NotificationChannel(
                CHANNEL_VPN_STATUS,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows whether PrivacyGuard VPN is active"
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_ALERTS,
                "Privacy Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts for suspicious or blocked connections"
            },
            NotificationChannel(
                CHANNEL_WEEKLY_REPORT,
                "Weekly Report",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Weekly privacy summary"
                setShowBadge(false)
            },
        )

        channels.forEach { manager.createNotificationChannel(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VPN Foreground Notification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the required foreground service notification shown while the VPN is active.
     *
     * @param blockedCount  number of trackers blocked in this session.
     * @param mainActivityClass class of the main activity (for tap-to-open).
     * @return a [Notification] ready to pass to [android.app.Service.startForeground].
     */
    fun buildVpnActiveNotification(
        blockedCount: Long,
        mainActivityClass: Class<*>,
    ): Notification {
        val tapIntent    = Intent(context, mainActivityClass)
        val pendingTap   = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent   = Intent(ACTION_STOP_VPN).setPackage(context.packageName)
        val pendingStop  = PendingIntent.getBroadcast(
            context, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val body = when {
            blockedCount == 0L -> "Monitoring your connections…"
            blockedCount == 1L -> "1 tracker blocked"
            else               -> "$blockedCount trackers blocked"
        }

        return NotificationCompat.Builder(context, CHANNEL_VPN_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("PrivacyGuard Active")
            .setContentText(body)
            .setContentIntent(pendingTap)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                pendingStop,
            )
            .build()
    }

    /**
     * Updates the content of the foreground notification without flickering.
     * Requires notification id [NOTIFICATION_ID_VPN].
     */
    fun updateVpnNotification(blockedCount: Long, mainActivityClass: Class<*>) {
        val notification = buildVpnActiveNotification(blockedCount, mainActivityClass)
        manager.notify(NOTIFICATION_ID_VPN, notification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Alert Notifications (suspicious connection detected)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Posts a high-priority alert that [appName] attempted to reach [domain].
     *
     * @param mainActivityClass for tap-to-open.
     */
    fun postSuspiciousAlert(
        appName: String,
        domain: String,
        mainActivityClass: Class<*>,
    ) {
        val tapIntent  = Intent(context, mainActivityClass)
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_ALERT, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Suspicious connection blocked")
            .setContentText("$appName → $domain")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$appName tried to connect to $domain, which was blocked by your privacy rules.")
            )
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(NOTIFICATION_ID_ALERT, notification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Weekly Report Notification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Posts the weekly privacy report summary.
     *
     * @param totalBlocked total blocked connections this week.
     * @param topApp       package name of the most tracked app this week (may be null).
     */
    fun postWeeklyReport(
        totalBlocked: Long,
        topApp: String?,
        mainActivityClass: Class<*>,
    ) {
        val tapIntent  = Intent(context, mainActivityClass)
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_REPORT, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val detail = if (topApp != null)
            "Most tracked app this week: $topApp"
        else
            "Tap to see your privacy summary."

        val notification = NotificationCompat.Builder(context, CHANNEL_WEEKLY_REPORT)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Your Weekly Privacy Report")
            .setContentText("$totalBlocked trackers blocked this week")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$totalBlocked trackers were blocked this week.\n$detail")
            )
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID_REPORT, notification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cancel
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Posts a weekly report notification. Convenience wrapper for [postWeeklyReport].
     */
    fun showWeeklyReportNotification(blockedCount: Int, topApps: List<String>) {
        postWeeklyReport(
            totalBlocked      = blockedCount.toLong(),
            topApp            = topApps.firstOrNull(),
            mainActivityClass = try {
                Class.forName("com.privacyguard.app.MainActivity")
            } catch (_: ClassNotFoundException) {
                NotificationHelper::class.java
            },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JA3 Threat Alert
    // ─────────────────────────────────────────────────────────────────────────

    fun postJa3ThreatAlert(
        malwareName: String,
        packageName: String?,
        sni: String?,
        mainActivityClass: Class<*>,
    ) {
        val tapIntent  = Intent(context, mainActivityClass)
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_JA3_THREAT, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val appLabel = packageName?.substringAfterLast('.') ?: "Unknown app"
        val body = buildString {
            append(appLabel)
            if (!sni.isNullOrBlank()) append(" → $sni")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("TLS Fingerprint Match: $malwareName")
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$appLabel made a TLS connection matching the \"$malwareName\" fingerprint. Check Crypto tab for details.")
            )
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFICATION_ID_JA3_THREAT, notification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Kill Switch Alert
    // ─────────────────────────────────────────────────────────────────────────

    fun postKillSwitchAlert() {
        val mainActivityClass = try {
            Class.forName("com.privacyguard.app.MainActivity")
        } catch (_: ClassNotFoundException) {
            NotificationHelper::class.java
        }

        val tapIntent  = Intent(context, mainActivityClass)
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_KILL_SWITCH, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("VPN Disconnected — Kill Switch Active")
            .setContentText("All connections are blocked until VPN reconnects.")
            .setContentIntent(pendingTap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(NOTIFICATION_ID_KILL_SWITCH, notification)
    }

    fun cancelKillSwitchAlert() = manager.cancel(NOTIFICATION_ID_KILL_SWITCH)

    fun cancelVpnNotification()    = manager.cancel(NOTIFICATION_ID_VPN)
    fun cancelAlertNotification()  = manager.cancel(NOTIFICATION_ID_ALERT)
    fun cancelReportNotification() = manager.cancel(NOTIFICATION_ID_REPORT)
    fun cancelAll()                = manager.cancelAll()

    // ─────────────────────────────────────────────────────────────────────────
    // Companion — IDs & Channel Names
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val CHANNEL_VPN_STATUS    = "pg_vpn_status"
        const val CHANNEL_ALERTS        = "pg_alerts"
        const val CHANNEL_WEEKLY_REPORT = "pg_weekly_report"

        const val NOTIFICATION_ID_VPN          = 1
        const val NOTIFICATION_ID_ALERT        = 2
        const val NOTIFICATION_ID_REPORT       = 3
        const val NOTIFICATION_ID_KILL_SWITCH  = 4
        const val NOTIFICATION_ID_JA3_THREAT   = 5

        /** Broadcast action to stop the VPN from the notification's Stop button. */
        const val ACTION_STOP_VPN = "com.privacyguard.action.STOP_VPN"
    }
}