package com.privacyguard.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.privacyguard.app.domain.model.NotificationType
import com.privacyguard.core.filter.FilterEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsPreferences private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SettingsPreferences? = null

        private const val PREFS_NAME = "privacyguard_settings"
        private const val KEY_NOTIFICATIONS_ENABLED      = "notifications_enabled"
        private const val KEY_BLOCK_NOTIFICATIONS        = "block_notifications"
        private const val KEY_TRACKER_NOTIFICATIONS      = "tracker_notifications"
        private const val KEY_VPN_NOTIFICATIONS          = "vpn_notifications"
        private const val KEY_WEEKLY_REPORT              = "weekly_report"
        private const val KEY_KILL_SWITCH_NOTIFICATIONS  = "kill_switch_notifications"
        private const val KEY_KILL_SWITCH_ENABLED        = "kill_switch_enabled"
        private const val KEY_DOH_ENABLED                = "doh_enabled"
        private const val KEY_DOH_PROVIDER               = "doh_provider"
        private const val KEY_RETENTION_DAYS             = "retention_days"
        private const val KEY_UPSTREAM_DNS               = "upstream_dns"
        private const val KEY_ONBOARDING_COMPLETED       = "onboarding_completed"
        private const val KEY_PROTECTION_LEVEL           = "protection_level"

        const val DOH_CLOUDFLARE = "https://cloudflare-dns.com/dns-query"
        const val DOH_GOOGLE     = "https://dns.google/dns-query"
        const val DOH_QUAD9      = "https://dns.quad9.net/dns-query"

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

    private val _killSwitchEnabled = MutableStateFlow(prefs.getBoolean(KEY_KILL_SWITCH_ENABLED, true))
    val killSwitchEnabled: StateFlow<Boolean> = _killSwitchEnabled.asStateFlow()

    private val _dohEnabled = MutableStateFlow(prefs.getBoolean(KEY_DOH_ENABLED, false))
    val dohEnabled: StateFlow<Boolean> = _dohEnabled.asStateFlow()

    private val _dohProvider = MutableStateFlow(prefs.getString(KEY_DOH_PROVIDER, DOH_CLOUDFLARE) ?: DOH_CLOUDFLARE)
    val dohProvider: StateFlow<String> = _dohProvider.asStateFlow()

    private val _retentionDays = MutableStateFlow(prefs.getInt(KEY_RETENTION_DAYS, 30))
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    private val _upstreamDns = MutableStateFlow(prefs.getString(KEY_UPSTREAM_DNS, "1.1.1.1") ?: "1.1.1.1")
    val upstreamDns: StateFlow<String> = _upstreamDns.asStateFlow()

    private val _onboardingCompleted = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false))
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    private val _protectionLevel = MutableStateFlow(
        prefs.getString(KEY_PROTECTION_LEVEL, FilterEngine.BlockLevel.STANDARD.name)
            ?: FilterEngine.BlockLevel.STANDARD.name
    )
    val protectionLevel: StateFlow<String> = _protectionLevel.asStateFlow()

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

    fun setKillSwitchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KILL_SWITCH_ENABLED, enabled).apply()
        _killSwitchEnabled.value = enabled
    }

    fun setDohEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DOH_ENABLED, enabled).apply()
        _dohEnabled.value = enabled
    }

    fun setDohProvider(provider: String) {
        prefs.edit().putString(KEY_DOH_PROVIDER, provider).apply()
        _dohProvider.value = provider
    }

    fun setRetentionDays(days: Int) {
        prefs.edit().putInt(KEY_RETENTION_DAYS, days).apply()
        _retentionDays.value = days
    }

    fun setUpstreamDns(dns: String) {
        prefs.edit().putString(KEY_UPSTREAM_DNS, dns).apply()
        _upstreamDns.value = dns
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
        _onboardingCompleted.value = completed
    }

    fun setProtectionLevel(level: String) {
        prefs.edit().putString(KEY_PROTECTION_LEVEL, level).apply()
        _protectionLevel.value = level
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
