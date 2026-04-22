package com.privacyguard.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.privacyguard.app.domain.model.NotificationType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsPreferences private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SettingsPreferences? = null

        private const val PREFS_NAME = "privacyguard_settings"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_BLOCK_NOTIFICATIONS = "block_notifications"
        private const val KEY_TRACKER_NOTIFICATIONS = "tracker_notifications"
        private const val KEY_VPN_NOTIFICATIONS = "vpn_notifications"
        private const val KEY_WEEKLY_REPORT = "weekly_report"
        private const val KEY_KILL_SWITCH_NOTIFICATIONS = "kill_switch_notifications"

        fun getInstance(context: Context): SettingsPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _notificationsEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _blockNotifications = MutableStateFlow(prefs.getBoolean(KEY_BLOCK_NOTIFICATIONS, true))
    val blockNotifications: StateFlow<Boolean> = _blockNotifications.asStateFlow()

    private val _trackerNotifications = MutableStateFlow(prefs.getBoolean(KEY_TRACKER_NOTIFICATIONS, true))
    val trackerNotifications: StateFlow<Boolean> = _trackerNotifications.asStateFlow()

    private val _vpnNotifications = MutableStateFlow(prefs.getBoolean(KEY_VPN_NOTIFICATIONS, true))
    val vpnNotifications: StateFlow<Boolean> = _vpnNotifications.asStateFlow()

    private val _weeklyReport = MutableStateFlow(prefs.getBoolean(KEY_WEEKLY_REPORT, true))
    val weeklyReport: StateFlow<Boolean> = _weeklyReport.asStateFlow()

    private val _killSwitchNotifications = MutableStateFlow(prefs.getBoolean(KEY_KILL_SWITCH_NOTIFICATIONS, true))
    val killSwitchNotifications: StateFlow<Boolean> = _killSwitchNotifications.asStateFlow()

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
        _notificationsEnabled.value = enabled
    }

    fun setBlockNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_NOTIFICATIONS, enabled).apply()
        _blockNotifications.value = enabled
    }

    fun setTrackerNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRACKER_NOTIFICATIONS, enabled).apply()
        _trackerNotifications.value = enabled
    }

    fun setVpnNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VPN_NOTIFICATIONS, enabled).apply()
        _vpnNotifications.value = enabled
    }

    fun setWeeklyReport(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEEKLY_REPORT, enabled).apply()
        _weeklyReport.value = enabled
    }

    fun setKillSwitchNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KILL_SWITCH_NOTIFICATIONS, enabled).apply()
        _killSwitchNotifications.value = enabled
    }

    fun shouldShowNotification(type: NotificationType): Boolean {
        if (!notificationsEnabled.value) return false
        return when (type) {
            NotificationType.CONNECTION_BLOCKED,
            NotificationType.APP_BLOCKED -> blockNotifications.value
            NotificationType.TRACKER_BLOCKED -> trackerNotifications.value
            NotificationType.VPN_STARTED,
            NotificationType.VPN_STOPPED -> vpnNotifications.value
            NotificationType.WEEKLY_REPORT -> weeklyReport.value
            NotificationType.KILL_SWITCH_ACTIVATED -> killSwitchNotifications.value
            else -> true
        }
    }
}
