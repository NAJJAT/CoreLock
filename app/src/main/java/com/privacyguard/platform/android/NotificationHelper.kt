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
import com.privacyguard.app.R
import com.privacyguard.app.core.locale.AppLanguage
import com.privacyguard.vpn.interception.InspectionStatus
import com.privacyguard.vpn.interception.TlsInterceptionFactory

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
class NotificationHelper(private val baseContext: Context) {

    // In the in-app language: below Android 13 a service context otherwise stays in the
    // phone's language. Read on each use so a change applies to the next notification.
    private val context: Context get() = AppLanguage.localized(baseContext)

    /**
     * The Settings "Notifications" switch silences every alert and summary. The VPN
     * drop alert is the exception: it has its own switch and exists to say that
     * protection is off.
     */
    private fun muted(): Boolean =
        !com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(context).notificationsEnabled.value

    /** The app's own label ("Facebook"), never a package fragment ("katana"). */
    private fun appLabel(packageName: String?): String {
        if (packageName.isNullOrBlank()) return context.getString(R.string.notif_unknown_app)
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrElse { com.privacyguard.app.ui.traffic.AppNames.label(context, packageName) }
    }

    /** Settings → "Show details in notifications" (off by default). */
    private fun showDetails(): Boolean =
        com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(context).notificationDetails.value

    /**
     * Without "Show details in notifications", an alert says only what kind of alert it
     * is: no app, host or malware name, on the lock screen or for apps that read
     * notifications (V-09). Applied last, so it replaces whatever the alert set.
     */
    private fun NotificationCompat.Builder.detailsOnlyIfAllowed(kindTitle: Int): NotificationCompat.Builder =
        if (showDetails()) this
        else setContentTitle(context.getString(kindTitle))
            .setContentText(context.getString(R.string.notif_open_for_details))
            .setStyle(null)

    private fun quantity(id: Int, n: Long): String =
        context.resources.getQuantityString(id, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), n)

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
                context.getString(R.string.notif_ch_status),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_ch_status_desc)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notif_ch_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_ch_alerts_desc)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
            NotificationChannel(
                CHANNEL_WEEKLY_REPORT,
                context.getString(R.string.notif_ch_weekly),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.notif_ch_weekly_desc)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
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
     * @param blockedCount  blocks in the last 24 h (BlockStats), the dashboard's number.
     * @param mainActivityClass class of the main activity (for tap-to-open).
     * @param inspection HTTPS inspection state; anything but OFF is always shown, so
     *   decryption is never silent (and says when the organisation turned it on).
     * @return a [Notification] ready to pass to [android.app.Service.startForeground].
     */
    fun buildVpnActiveNotification(
        blockedCount: Long,
        mainActivityClass: Class<*>,
        inspection: InspectionStatus = TlsInterceptionFactory.status(context),
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
            // Lookups and connections alike, so it says "blocked", not "trackers".
            blockedCount == 0L -> context.getString(R.string.notif_monitoring)
            else               -> quantity(R.plurals.notif_blocked_n, blockedCount)
        }

        val inspectionText = when (inspection) {
            InspectionStatus.OFF -> null
            InspectionStatus.ON -> context.getString(R.string.notif_inspection_on)
            InspectionStatus.ON_BY_ORGANISATION -> context.getString(R.string.notif_inspection_on_org)
        }

        return PrivateNotifications.builder(context, CHANNEL_VPN_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(context.getString(R.string.notif_active))
            .setContentText(body)
            .setSubText(inspectionText)
            .setContentIntent(pendingTap)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                android.R.drawable.ic_delete,
                context.getString(R.string.notif_stop),
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
        if (muted()) return
        val tapIntent  = Intent(context, mainActivityClass)
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_ALERT, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = PrivateNotifications.builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notif_suspicious_title))
            .setContentText(context.getString(R.string.notif_app_to_domain, appName, domain))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_suspicious_body, appName, domain))
            )
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .detailsOnlyIfAllowed(R.string.notif_suspicious_title)
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
        if (muted()) return
        val tapIntent  = Intent(context, mainActivityClass)
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_REPORT, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val detail = if (topApp != null && showDetails())
            context.getString(R.string.notif_weekly_top, appLabel(topApp))
        else
            context.getString(R.string.notif_weekly_tap)

        val notification = PrivateNotifications.builder(context, CHANNEL_WEEKLY_REPORT)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.notif_weekly_title))
            .setContentText(quantity(R.plurals.notif_weekly_blocked, totalBlocked))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(quantity(R.plurals.notif_weekly_blocked, totalBlocked) + "\n" + detail)
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
        if (muted()) return
        val tapIntent  = Intent(context, mainActivityClass).putExtra(EXTRA_NAV_ROUTE, "alerts")
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_JA3_THREAT, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val appLabel = appLabel(packageName)
        val body = buildString {
            append(appLabel)
            if (!sni.isNullOrBlank()) append(" · $sni") // direction-neutral separator, reads correctly in RTL
        }
        val notification = PrivateNotifications.builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notif_ja3_title, malwareName))
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_ja3_body, appLabel, malwareName))
            )
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .detailsOnlyIfAllowed(R.string.notif_kind_malware)
            .build()
        manager.notify(NOTIFICATION_ID_JA3_THREAT, notification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TLS fingerprint baseline change
    // ─────────────────────────────────────────────────────────────────────────

    /** One notification per app, so two apps changing at once both stay visible. */
    fun postTlsFingerprintChange(
        appLabel: String,
        packageName: String,
        sni: String?,
        mainActivityClass: Class<*>,
    ) {
        if (muted()) return
        val id = NOTIFICATION_ID_TLS_FP_BASE + (packageName.hashCode() and 0xFFF)
        val tapIntent = Intent(context, mainActivityClass).putExtra(EXTRA_NAV_ROUTE, "crypto")
        val pendingTap = PendingIntent.getActivity(
            context, id, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = if (sni.isNullOrBlank())
            context.getString(com.privacyguard.app.R.string.tls_fp_alert_what_no_domain, appLabel)
        else
            context.getString(com.privacyguard.app.R.string.tls_fp_alert_what_domain, appLabel, sni)
        val body = listOf(
            what,
            context.getString(com.privacyguard.app.R.string.tls_fp_alert_reasons),
            context.getString(com.privacyguard.app.R.string.tls_fp_alert_actions),
        ).joinToString("\n\n")
        val notification = PrivateNotifications.builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(com.privacyguard.app.R.string.tls_fp_alert_title, appLabel))
            .setContentText(context.getString(com.privacyguard.app.R.string.tls_fp_alert_short))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .detailsOnlyIfAllowed(R.string.notif_kind_tls_fp)
            .build()
        manager.notify(id, notification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Behavior DNA Alert
    // ─────────────────────────────────────────────────────────────────────────

    fun postBehaviorAlert(
        packageName: String,
        title: String,
        summary: String,
        mainActivityClass: Class<*>,
    ) {
        if (muted()) return
        val tapIntent = Intent(context, mainActivityClass).putExtra(EXTRA_NAV_ROUTE, "alerts")
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_BEHAVIOR, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val appLabel = appLabel(packageName)
        val notification = PrivateNotifications.builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notif_behavior_title, appLabel))
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .detailsOnlyIfAllowed(R.string.notif_kind_behavior)
            .build()
        manager.notify(NOTIFICATION_ID_BEHAVIOR, notification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleartext Connection Alert
    // ─────────────────────────────────────────────────────────────────────────

    fun postCleartextAlert(
        packageName: String,
        destination: String,
        mainActivityClass: Class<*>,
    ) {
        if (muted()) return
        val tapIntent = Intent(context, mainActivityClass).putExtra(EXTRA_NAV_ROUTE, "connections")
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_CLEARTEXT, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val appLabel = appLabel(packageName)
        val notification = PrivateNotifications.builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notif_cleartext_title, appLabel))
            .setContentText(context.getString(R.string.notif_cleartext_text, appLabel, destination))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_cleartext_body, appLabel, destination)))
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .detailsOnlyIfAllowed(R.string.notif_kind_cleartext)
            .build()
        manager.notify(NOTIFICATION_ID_CLEARTEXT, notification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Certificate Transparency Alert
    // ─────────────────────────────────────────────────────────────────────────

    fun postCtCertAlert(
        domain: String,
        newCertCount: Int,
        mainActivityClass: Class<*>,
    ) {
        if (muted()) return
        val tapIntent  = Intent(context, mainActivityClass).putExtra(EXTRA_NAV_ROUTE, "alerts")
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_CT_CERT, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = context.resources.getQuantityString(R.plurals.notif_ct_body, newCertCount, newCertCount, domain)
        val notification = PrivateNotifications.builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notif_ct_title, domain))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_ct_tap, body)))
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .detailsOnlyIfAllowed(R.string.notif_kind_ct)
            .build()
        manager.notify(NOTIFICATION_ID_CT_CERT, notification)
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

        val notification = PrivateNotifications.builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(context.getString(R.string.notif_vpn_down_title))
            // Only Android's lockdown mode blocks traffic without the tunnel; say which applies.
            .setContentText(context.getString(
                if (com.privacyguard.app.vpn.KillSwitch.lockdownActive) R.string.notif_vpn_down_blocked
                else R.string.notif_vpn_down_open
            ))
            .setContentIntent(pendingTap)
            .setOngoing(true)
            // Always-on VPN is what restarts protection; this alert only tells.
            .addAction(0, context.getString(R.string.set_open_vpn_settings), PendingIntent.getActivity(
                context, NOTIFICATION_ID_KILL_SWITCH + 1,
                Intent(android.provider.Settings.ACTION_VPN_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(NOTIFICATION_ID_KILL_SWITCH, notification)
    }

    fun cancelKillSwitchAlert() = manager.cancel(NOTIFICATION_ID_KILL_SWITCH)

    /**
     * Once, when the organisation's MDM starts HTTPS inspection (ManagedConfigApplier).
     * Not silenced by the Notifications switch: like "protection stopped", it is
     * something the user must know. Names no app or server.
     */
    fun postInspectionEnabledByOrganisation(mainActivityClass: Class<*>) {
        createChannels()
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_INSPECTION_BY_ORG, Intent(context, mainActivityClass),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = PrivateNotifications.builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(context.getString(R.string.notif_inspection_org_title))
            .setContentText(context.getString(R.string.notif_inspection_org_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_inspection_org_text)))
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID_INSPECTION_BY_ORG, notification)
    }

    /**
     * Protection died with the process (see ProtectionWatchdog). Not silenced by the
     * Notifications switch: like the VPN drop alert it says protection is off. A tap
     * starts protection again (a notification tap may start a foreground service).
     */
    fun postProtectionStopped() = postStopped(R.string.notif_protection_stopped_text)

    /**
     * Android stopped protection because the user turned Always-on VPN off in its
     * settings (see PrivacyVpnService.onRevoke). A stop the user caused, said plainly;
     * nothing about lockdown or blocked connections. A tap turns protection back on.
     */
    fun postAlwaysOnTurnedOff() = postStopped(R.string.notif_always_on_turned_off_text)

    private fun postStopped(textRes: Int) {
        createChannels()  // may be the first thing a freshly started process does
        val start = PrivacyVpnService.startIntent(context)
        val restart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            PendingIntent.getForegroundService(context, NOTIFICATION_ID_PROTECTION_STOPPED, start,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else
            PendingIntent.getService(context, NOTIFICATION_ID_PROTECTION_STOPPED, start,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = PrivateNotifications.builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(context.getString(R.string.notif_vpn_down_title))
            .setContentText(context.getString(textRes))
            .setContentIntent(restart)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFICATION_ID_PROTECTION_STOPPED, notification)
    }

    fun cancelProtectionStopped() = manager.cancel(NOTIFICATION_ID_PROTECTION_STOPPED)

    fun postBackgroundBlockAlert(
        packageName: String,
        destination: String,
        mainActivityClass: Class<*>,
    ) {
        if (muted()) return
        val tapIntent = Intent(context, mainActivityClass).putExtra(EXTRA_NAV_ROUTE, "apps")
        val pendingTap = PendingIntent.getActivity(
            context, NOTIFICATION_ID_BACKGROUND_BLOCK, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val appLabel = appLabel(packageName)
        val notification = PrivateNotifications.builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notif_bg_title))
            .setContentText(context.getString(R.string.notif_bg_text, appLabel, destination))
            .setContentIntent(pendingTap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .detailsOnlyIfAllowed(R.string.notif_bg_title)
            .build()
        manager.notify(NOTIFICATION_ID_BACKGROUND_BLOCK, notification)
    }

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
        const val NOTIFICATION_ID_CT_CERT      = 6
        const val NOTIFICATION_ID_CLEARTEXT    = 7
        const val NOTIFICATION_ID_BEHAVIOR        = 8
        const val NOTIFICATION_ID_BACKGROUND_BLOCK = 9
        const val NOTIFICATION_ID_PROTECTION_STOPPED = 10
        const val NOTIFICATION_ID_INSPECTION_BY_ORG = 11
        // 1000..5095: one slot per app (see postTlsFingerprintChange).
        const val NOTIFICATION_ID_TLS_FP_BASE  = 1000

        /** Broadcast action to stop the VPN from the notification's Stop button. */
        const val ACTION_STOP_VPN = "com.privacyguard.action.STOP_VPN"

        /** Intent extra: nav route to open when tapping a notification (e.g. "crypto"). */
        const val EXTRA_NAV_ROUTE = "pg_nav_route"
    }
}