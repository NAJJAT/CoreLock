package com.privacyguard.app.domain.model

data class Notification(
    val id: String,
    val type: NotificationType,
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val action: NotificationAction? = null,
    val isRead: Boolean = false,
    val priority: NotificationPriority = NotificationPriority.NORMAL
) {

    companion object {
        fun vpnStarted(): Notification = Notification(
            id = "vpn_started_${System.currentTimeMillis()}",
            type = NotificationType.VPN_STARTED,
            title = "VPN Active",
            message = "Your privacy is now protected",
            priority = NotificationPriority.HIGH,
            action = NotificationAction.OpenApp("dashboard")
        )

        fun vpnStopped(): Notification = Notification(
            id = "vpn_stopped_${System.currentTimeMillis()}",
            type = NotificationType.VPN_STOPPED,
            title = "VPN Stopped",
            message = "Your device is no longer protected",
            priority = NotificationPriority.HIGH,
            action = NotificationAction.OpenApp("dashboard")
        )

        fun connectionBlocked(appName: String, domain: String): Notification = Notification(
            id = "blocked_${System.currentTimeMillis()}",
            type = NotificationType.CONNECTION_BLOCKED,
            title = "Connection Blocked",
            message = "$appName was blocked from connecting to $domain",
            priority = NotificationPriority.HIGH,
            action = NotificationAction.ViewConnections
        )

        fun trackerBlocked(trackerName: String, appName: String): Notification = Notification(
            id = "tracker_${System.currentTimeMillis()}",
            type = NotificationType.TRACKER_BLOCKED,
            title = "Tracker Blocked",
            message = "$trackerName was blocked from $appName",
            priority = NotificationPriority.NORMAL,
            action = NotificationAction.ViewStatistics
        )

        fun appBlocked(appName: String): Notification = Notification(
            id = "app_blocked_${System.currentTimeMillis()}",
            type = NotificationType.APP_BLOCKED,
            title = "App Blocked",
            message = "$appName has been blocked from accessing the internet",
            priority = NotificationPriority.HIGH,
            action = NotificationAction.OpenApp("apps")
        )

        fun killSwitchActivated(): Notification = Notification(
            id = "killswitch_${System.currentTimeMillis()}",
            type = NotificationType.KILL_SWITCH_ACTIVATED,
            title = "Kill Switch Activated",
            message = "PrivacyGuard is trying to restore protection after an unexpected VPN stop",
            priority = NotificationPriority.CRITICAL,
            action = NotificationAction.OpenApp("settings")
        )

        fun weeklyReport(blockedCount: Int, topApps: List<String>): Notification = Notification(
            id = "weekly_${System.currentTimeMillis()}",
            type = NotificationType.WEEKLY_REPORT,
            title = "Weekly Privacy Report",
            message = "Blocked $blockedCount trackers this week. Top apps: ${topApps.take(3).joinToString(", ")}",
            priority = NotificationPriority.NORMAL,
            action = NotificationAction.OpenApp("statistics")
        )

        fun blocklistUpdated(source: String, count: Int): Notification = Notification(
            id = "blocklist_${System.currentTimeMillis()}",
            type = NotificationType.BLOCKLIST_UPDATED,
            title = "Blocklist Updated",
            message = "$source: $count new domains added",
            priority = NotificationPriority.LOW
        )
    }
}

enum class NotificationType {
    VPN_STARTED,
    VPN_STOPPED,
    CONNECTION_BLOCKED,
    TRACKER_BLOCKED,
    APP_BLOCKED,
    KILL_SWITCH_ACTIVATED,
    WEEKLY_REPORT,
    BLOCKLIST_UPDATED,
    PERMISSION_REQUIRED,
    ERROR
}

enum class NotificationPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

sealed class NotificationAction {
    data object Dismiss : NotificationAction()

    data class OpenApp(val screen: String) : NotificationAction()

    data object ViewConnections : NotificationAction()

    data object ViewStatistics : NotificationAction()

    data class BlockApp(val packageName: String) : NotificationAction()

    data class ViewConnection(val connectionId: String) : NotificationAction()
}
