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
        private const val KEY_VPN_CONSENT_TS             = "vpn_consent_ts"
        private const val KEY_TLS_CONSENT_TS             = "tls_consent_ts"
        private const val KEY_PROTECTION_LEVEL           = "protection_level"
        private const val KEY_ENTERPRISE_INSPECTION_ENABLED = "enterprise_inspection_enabled"
        private const val KEY_ENTERPRISE_CONSENT_TS         = "enterprise_consent_ts"
        private const val KEY_ENTERPRISE_SIEM_ENDPOINT      = "enterprise_siem_endpoint"
        private const val KEY_ENTERPRISE_SIEM_API_KEY       = "enterprise_siem_api_key"
        private const val KEY_ENTERPRISE_SHIP_ENABLED       = "enterprise_ship_enabled"
        private const val KEY_ENTERPRISE_LOCAL_LOG_ENABLED  = "enterprise_local_log_enabled"

        // MITM Configuration Keys
        private const val KEY_MITM_ENABLED = "mitm_enabled"
        private const val KEY_MITM_SKIP_PINNED_APPS = "mitm_skip_pinned_apps"
        private const val KEY_MITM_REDACT_PII = "mitm_redact_pii"
        private const val KEY_MITM_SIEM_ENDPOINT = "mitm_siem_endpoint"
        private const val KEY_MITM_SIEM_API_KEY = "mitm_siem_api_key"
        private const val KEY_MITM_MAX_PAYLOAD_SIZE = "mitm_max_payload_size"
        private const val KEY_MITM_SHIP_TO_SIEM = "mitm_ship_to_siem"
        private const val KEY_MITM_WRITE_LOCAL_LOG = "mitm_write_local_log"
        private const val KEY_MITM_RETENTION_DAYS = "mitm_retention_days"
        private const val KEY_MITM_CONSENT_TIMESTAMP = "mitm_consent_timestamp"

        // Dual VPN
        private const val KEY_DUAL_VPN_ENABLED     = "dual_vpn_enabled"
        private const val KEY_DUAL_VPN_HOP1_HOST   = "dual_vpn_hop1_host"
        private const val KEY_DUAL_VPN_HOP1_PORT   = "dual_vpn_hop1_port"
        private const val KEY_DUAL_VPN_HOP1_USER   = "dual_vpn_hop1_user"
        private const val KEY_DUAL_VPN_HOP1_PASS   = "dual_vpn_hop1_pass"
        private const val KEY_DUAL_VPN_HOP2_HOST   = "dual_vpn_hop2_host"
        private const val KEY_DUAL_VPN_HOP2_PORT   = "dual_vpn_hop2_port"
        private const val KEY_DUAL_VPN_HOP2_USER   = "dual_vpn_hop2_user"
        private const val KEY_DUAL_VPN_HOP2_PASS   = "dual_vpn_hop2_pass"

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

    // ==================== EXISTING FLOWS ====================

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

    private val _vpnConsentTimestampMs = MutableStateFlow(prefs.getLong(KEY_VPN_CONSENT_TS, 0L))
    val vpnConsentTimestampMs: StateFlow<Long> = _vpnConsentTimestampMs.asStateFlow()

    private val _tlsConsentTimestampMs = MutableStateFlow(prefs.getLong(KEY_TLS_CONSENT_TS, 0L))
    val tlsConsentTimestampMs: StateFlow<Long> = _tlsConsentTimestampMs.asStateFlow()

    private val _protectionLevel = MutableStateFlow(
        prefs.getString(KEY_PROTECTION_LEVEL, FilterEngine.BlockLevel.STANDARD.name)
            ?: FilterEngine.BlockLevel.STANDARD.name
    )
    val protectionLevel: StateFlow<String> = _protectionLevel.asStateFlow()

    private val _enterpriseInspectionEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_ENTERPRISE_INSPECTION_ENABLED, false)
    )
    val enterpriseInspectionEnabled: StateFlow<Boolean> = _enterpriseInspectionEnabled.asStateFlow()

    private val _enterpriseConsentTimestampMs = MutableStateFlow(
        prefs.getLong(KEY_ENTERPRISE_CONSENT_TS, 0L)
    )
    val enterpriseConsentTimestampMs: StateFlow<Long> = _enterpriseConsentTimestampMs.asStateFlow()

    private val _enterpriseSiemEndpoint = MutableStateFlow(
        prefs.getString(KEY_ENTERPRISE_SIEM_ENDPOINT, "") ?: ""
    )
    val enterpriseSiemEndpoint: StateFlow<String> = _enterpriseSiemEndpoint.asStateFlow()

    private val _enterpriseSiemApiKey = MutableStateFlow(
        prefs.getString(KEY_ENTERPRISE_SIEM_API_KEY, "") ?: ""
    )
    val enterpriseSiemApiKey: StateFlow<String> = _enterpriseSiemApiKey.asStateFlow()

    private val _enterpriseShipEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_ENTERPRISE_SHIP_ENABLED, false)
    )
    val enterpriseShipEnabled: StateFlow<Boolean> = _enterpriseShipEnabled.asStateFlow()

    private val _enterpriseLocalLogEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_ENTERPRISE_LOCAL_LOG_ENABLED, true)
    )
    val enterpriseLocalLogEnabled: StateFlow<Boolean> = _enterpriseLocalLogEnabled.asStateFlow()

    // ==================== MITM FLOWS (NEW) ====================

    private val _mitmEnabled = MutableStateFlow(prefs.getBoolean(KEY_MITM_ENABLED, false))
    val mitmEnabled: StateFlow<Boolean> = _mitmEnabled.asStateFlow()

    private val _mitmSkipPinnedApps = MutableStateFlow(prefs.getBoolean(KEY_MITM_SKIP_PINNED_APPS, true))
    val mitmSkipPinnedApps: StateFlow<Boolean> = _mitmSkipPinnedApps.asStateFlow()

    private val _mitmRedactPii = MutableStateFlow(prefs.getBoolean(KEY_MITM_REDACT_PII, true))
    val mitmRedactPii: StateFlow<Boolean> = _mitmRedactPii.asStateFlow()

    private val _mitmSiemEndpoint = MutableStateFlow(prefs.getString(KEY_MITM_SIEM_ENDPOINT, "") ?: "")
    val mitmSiemEndpoint: StateFlow<String> = _mitmSiemEndpoint.asStateFlow()

    private val _mitmSiemApiKey = MutableStateFlow(prefs.getString(KEY_MITM_SIEM_API_KEY, "") ?: "")
    val mitmSiemApiKey: StateFlow<String> = _mitmSiemApiKey.asStateFlow()

    private val _mitmMaxPayloadSize = MutableStateFlow(prefs.getInt(KEY_MITM_MAX_PAYLOAD_SIZE, 32768))
    val mitmMaxPayloadSize: StateFlow<Int> = _mitmMaxPayloadSize.asStateFlow()

    private val _mitmShipToSiem = MutableStateFlow(prefs.getBoolean(KEY_MITM_SHIP_TO_SIEM, false))
    val mitmShipToSiem: StateFlow<Boolean> = _mitmShipToSiem.asStateFlow()

    private val _mitmWriteLocalLog = MutableStateFlow(prefs.getBoolean(KEY_MITM_WRITE_LOCAL_LOG, true))
    val mitmWriteLocalLog: StateFlow<Boolean> = _mitmWriteLocalLog.asStateFlow()

    private val _mitmRetentionDays = MutableStateFlow(prefs.getInt(KEY_MITM_RETENTION_DAYS, 7))
    val mitmRetentionDays: StateFlow<Int> = _mitmRetentionDays.asStateFlow()

    private val _mitmConsentTimestamp = MutableStateFlow(prefs.getLong(KEY_MITM_CONSENT_TIMESTAMP, 0L))
    val mitmConsentTimestamp: StateFlow<Long> = _mitmConsentTimestamp.asStateFlow()

    // ==================== DUAL VPN FLOWS ====================

    private val _dualVpnEnabled = MutableStateFlow(prefs.getBoolean(KEY_DUAL_VPN_ENABLED, false))
    val dualVpnEnabled: StateFlow<Boolean> = _dualVpnEnabled.asStateFlow()

    private val _dualVpnHop1Host = MutableStateFlow(prefs.getString(KEY_DUAL_VPN_HOP1_HOST, "") ?: "")
    val dualVpnHop1Host: StateFlow<String> = _dualVpnHop1Host.asStateFlow()

    private val _dualVpnHop1Port = MutableStateFlow(prefs.getInt(KEY_DUAL_VPN_HOP1_PORT, 1080))
    val dualVpnHop1Port: StateFlow<Int> = _dualVpnHop1Port.asStateFlow()

    private val _dualVpnHop1User = MutableStateFlow(prefs.getString(KEY_DUAL_VPN_HOP1_USER, "") ?: "")
    val dualVpnHop1User: StateFlow<String> = _dualVpnHop1User.asStateFlow()

    private val _dualVpnHop1Pass = MutableStateFlow(prefs.getString(KEY_DUAL_VPN_HOP1_PASS, "") ?: "")
    val dualVpnHop1Pass: StateFlow<String> = _dualVpnHop1Pass.asStateFlow()

    private val _dualVpnHop2Host = MutableStateFlow(prefs.getString(KEY_DUAL_VPN_HOP2_HOST, "") ?: "")
    val dualVpnHop2Host: StateFlow<String> = _dualVpnHop2Host.asStateFlow()

    private val _dualVpnHop2Port = MutableStateFlow(prefs.getInt(KEY_DUAL_VPN_HOP2_PORT, 1080))
    val dualVpnHop2Port: StateFlow<Int> = _dualVpnHop2Port.asStateFlow()

    private val _dualVpnHop2User = MutableStateFlow(prefs.getString(KEY_DUAL_VPN_HOP2_USER, "") ?: "")
    val dualVpnHop2User: StateFlow<String> = _dualVpnHop2User.asStateFlow()

    private val _dualVpnHop2Pass = MutableStateFlow(prefs.getString(KEY_DUAL_VPN_HOP2_PASS, "") ?: "")
    val dualVpnHop2Pass: StateFlow<String> = _dualVpnHop2Pass.asStateFlow()

    // ==================== EXISTING SETTERS ====================

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

    fun recordVpnConsent() {
        val ts = System.currentTimeMillis()
        prefs.edit().putLong(KEY_VPN_CONSENT_TS, ts).apply()
        _vpnConsentTimestampMs.value = ts
    }

    fun recordTlsConsent() {
        val ts = System.currentTimeMillis()
        prefs.edit().putLong(KEY_TLS_CONSENT_TS, ts).apply()
        _tlsConsentTimestampMs.value = ts
    }

    val hasVpnConsent: Boolean get() = _vpnConsentTimestampMs.value > 0L
    val hasTlsConsent: Boolean get() = _tlsConsentTimestampMs.value > 0L

    fun setProtectionLevel(level: String) {
        prefs.edit().putString(KEY_PROTECTION_LEVEL, level).apply()
        _protectionLevel.value = level
    }

    fun setEnterpriseInspectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENTERPRISE_INSPECTION_ENABLED, enabled).apply()
        _enterpriseInspectionEnabled.value = enabled
    }

    fun setEnterpriseConsentTimestampMs(timestampMs: Long) {
        prefs.edit().putLong(KEY_ENTERPRISE_CONSENT_TS, timestampMs).apply()
        _enterpriseConsentTimestampMs.value = timestampMs
    }

    fun setEnterpriseSiemEndpoint(endpoint: String) {
        prefs.edit().putString(KEY_ENTERPRISE_SIEM_ENDPOINT, endpoint).apply()
        _enterpriseSiemEndpoint.value = endpoint
    }

    fun setEnterpriseSiemApiKey(apiKey: String) {
        prefs.edit().putString(KEY_ENTERPRISE_SIEM_API_KEY, apiKey).apply()
        _enterpriseSiemApiKey.value = apiKey
    }

    fun setEnterpriseShipEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENTERPRISE_SHIP_ENABLED, enabled).apply()
        _enterpriseShipEnabled.value = enabled
    }

    fun setEnterpriseLocalLogEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENTERPRISE_LOCAL_LOG_ENABLED, enabled).apply()
        _enterpriseLocalLogEnabled.value = enabled
    }

    // ==================== MITM SETTERS (NEW) ====================

    fun setMitmEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MITM_ENABLED, enabled).apply()
        _mitmEnabled.value = enabled
    }

    fun setMitmSkipPinnedApps(skip: Boolean) {
        prefs.edit().putBoolean(KEY_MITM_SKIP_PINNED_APPS, skip).apply()
        _mitmSkipPinnedApps.value = skip
    }

    fun setMitmRedactPii(redact: Boolean) {
        prefs.edit().putBoolean(KEY_MITM_REDACT_PII, redact).apply()
        _mitmRedactPii.value = redact
    }

    fun setMitmSiemEndpoint(endpoint: String) {
        prefs.edit().putString(KEY_MITM_SIEM_ENDPOINT, endpoint).apply()
        _mitmSiemEndpoint.value = endpoint
    }

    fun setMitmSiemApiKey(apiKey: String) {
        prefs.edit().putString(KEY_MITM_SIEM_API_KEY, apiKey).apply()
        _mitmSiemApiKey.value = apiKey
    }

    fun setMitmMaxPayloadSize(size: Int) {
        val clampedSize = size.coerceIn(1024, 1024 * 1024) // 1KB to 1MB
        prefs.edit().putInt(KEY_MITM_MAX_PAYLOAD_SIZE, clampedSize).apply()
        _mitmMaxPayloadSize.value = clampedSize
    }

    fun setMitmShipToSiem(ship: Boolean) {
        prefs.edit().putBoolean(KEY_MITM_SHIP_TO_SIEM, ship).apply()
        _mitmShipToSiem.value = ship
    }

    fun setMitmWriteLocalLog(write: Boolean) {
        prefs.edit().putBoolean(KEY_MITM_WRITE_LOCAL_LOG, write).apply()
        _mitmWriteLocalLog.value = write
    }

    fun setMitmRetentionDays(days: Int) {
        val clampedDays = days.coerceIn(1, 90)
        prefs.edit().putInt(KEY_MITM_RETENTION_DAYS, clampedDays).apply()
        _mitmRetentionDays.value = clampedDays
    }

    fun setMitmConsentTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_MITM_CONSENT_TIMESTAMP, timestamp).apply()
        _mitmConsentTimestamp.value = timestamp
    }

    // ==================== DUAL VPN SETTERS ====================

    fun setDualVpnEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DUAL_VPN_ENABLED, enabled).apply()
        _dualVpnEnabled.value = enabled
    }
    fun setDualVpnHop1Host(host: String) { prefs.edit().putString(KEY_DUAL_VPN_HOP1_HOST, host).apply(); _dualVpnHop1Host.value = host }
    fun setDualVpnHop1Port(port: Int)    { prefs.edit().putInt(KEY_DUAL_VPN_HOP1_PORT, port).apply();    _dualVpnHop1Port.value = port }
    fun setDualVpnHop1User(user: String) { prefs.edit().putString(KEY_DUAL_VPN_HOP1_USER, user).apply(); _dualVpnHop1User.value = user }
    fun setDualVpnHop1Pass(pass: String) { prefs.edit().putString(KEY_DUAL_VPN_HOP1_PASS, pass).apply(); _dualVpnHop1Pass.value = pass }
    fun setDualVpnHop2Host(host: String) { prefs.edit().putString(KEY_DUAL_VPN_HOP2_HOST, host).apply(); _dualVpnHop2Host.value = host }
    fun setDualVpnHop2Port(port: Int)    { prefs.edit().putInt(KEY_DUAL_VPN_HOP2_PORT, port).apply();    _dualVpnHop2Port.value = port }
    fun setDualVpnHop2User(user: String) { prefs.edit().putString(KEY_DUAL_VPN_HOP2_USER, user).apply(); _dualVpnHop2User.value = user }
    fun setDualVpnHop2Pass(pass: String) { prefs.edit().putString(KEY_DUAL_VPN_HOP2_PASS, pass).apply(); _dualVpnHop2Pass.value = pass }

    fun getDualVpnConfig(): com.privacyguard.vpn.dualvpn.DualVpnConfig =
        com.privacyguard.vpn.dualvpn.DualVpnConfig(
            enabled        = _dualVpnEnabled.value,
            firstHopHost   = _dualVpnHop1Host.value,
            firstHopPort   = _dualVpnHop1Port.value,
            firstHopUser   = _dualVpnHop1User.value,
            firstHopPassword = _dualVpnHop1Pass.value,
            secondHopHost  = _dualVpnHop2Host.value,
            secondHopPort  = _dualVpnHop2Port.value,
            secondHopUser  = _dualVpnHop2User.value,
            secondHopPassword = _dualVpnHop2Pass.value,
        )

    /**
     * Check if MITM consent is still valid (within 90 days)
     */
    fun isMitmConsentValid(): Boolean {
        val timestamp = _mitmConsentTimestamp.value
        if (timestamp == 0L) return false
        val ninetyDaysAgo = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
        return timestamp > ninetyDaysAgo
    }

    /**
     * Get current MITM enabled state (synchronous for VPN packet processing)
     */
    fun isMitmEnabledSync(): Boolean = prefs.getBoolean(KEY_MITM_ENABLED, false)

    /**
     * Get current MITM redact PII setting (synchronous)
     */
    fun isMitmRedactPiiSync(): Boolean = prefs.getBoolean(KEY_MITM_REDACT_PII, true)

    /**
     * Get current MITM max payload size (synchronous)
     */
    fun getMitmMaxPayloadSizeSync(): Int = prefs.getInt(KEY_MITM_MAX_PAYLOAD_SIZE, 32768)

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