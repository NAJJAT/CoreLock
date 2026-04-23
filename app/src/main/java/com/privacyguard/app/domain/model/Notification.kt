package com.privacyguard.app.domain.model

data class Notification(
    val id:           String,
    val title:        String,
    val message:      String,
    val type:         NotificationType,
    val priority:     NotificationPriority = NotificationPriority.NORMAL,
    val action:       NotificationAction?  = null,
    val connectionId: String?              = null,
    val screen:       String?              = null,
    val isRead:       Boolean              = false,
    val timestamp:    Long                 = System.currentTimeMillis(),
)

enum class NotificationType {
    CONNECTION_BLOCKED,
    APP_BLOCKED,
    TRACKER_BLOCKED,
    VPN_STARTED,
    VPN_STOPPED,
    WEEKLY_REPORT,
    KILL_SWITCH_ACTIVATED,
    BLOCKLIST_UPDATED,
    SUSPICIOUS_CONNECTION,
    GENERAL,
}

enum class NotificationPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW,
}

sealed class NotificationAction {
    data class OpenApp(val screen: String)            : NotificationAction()
    data class BlockApp(val packageName: String)       : NotificationAction()
    data class ViewConnection(val connectionId: String): NotificationAction()
    object ViewConnections                             : NotificationAction()
    object ViewStatistics                              : NotificationAction()
    object Dismiss                                     : NotificationAction()
}
