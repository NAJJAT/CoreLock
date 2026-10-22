package com.privacyguard.app.domain.model

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
