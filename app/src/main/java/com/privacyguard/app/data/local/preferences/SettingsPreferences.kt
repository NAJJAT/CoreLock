package com.privacyguard.app.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import com.privacyguard.app.core.security.SecureSecretStore
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
        private const val KEY_ALLOW_SCREENSHOTS          = "allow_screenshots"
        private const val KEY_NOTIFICATION_DETAILS       = "notification_details"
        private const val KEY_AUTO_RESTART               = "auto_restart"
        private const val KEY_BLOCK_NOTIFICATIONS        = "block_notifications"
        private const val KEY_TRACKER_NOTIFICATIONS      = "tracker_notifications"
        private const val KEY_VPN_NOTIFICATIONS          = "vpn_notifications"
        private const val KEY_WEEKLY_REPORT              = "weekly_report"
        private const val KEY_KILL_SWITCH_NOTIFICATIONS  = "kill_switch_notifications"
        private const val KEY_DOH_ENABLED                = "doh_enabled"
        // Features that send data to a third party are opt-in (README: no external servers).
        private const val KEY_CT_MONITORING_ENABLED      = "ct_monitoring_enabled"
        private const val KEY_AD_SDK_BLOCKING_ENABLED    = "ad_sdk_blocking_enabled"
        private const val KEY_BLOCK_QUIC                 = "block_quic"
        private const val KEY_AD_CHECKLIST_DONE          = "ad_checklist_done"
        private const val KEY_STRICT_DISMISSED           = "strict_suggestion_dismissed"
        private const val KEY_RULE_SUGGESTIONS_DISMISSED = "rule_suggestions_dismissed"
        private const val KEY_EXTERNAL_IP_CHECK_ENABLED  = "external_ip_check_enabled"
        private const val KEY_DOH_PROVIDER               = "doh_provider"
        private const val KEY_RETENTION_DAYS             = "retention_days"
        private const val KEY_UPSTREAM_DNS               = "upstream_dns"
        private const val KEY_ONBOARDING_COMPLETED       = "onboarding_completed"
        private const val KEY_VPN_CONSENT_TS             = "vpn_consent_ts"
        // "tls_consent_ts" (old onboarding flag, never read) is no longer written; the
        // real HTTPS-inspection consent lives in the enterprise MitmConfig.
        private const val KEY_PROTECTION_LEVEL           = "protection_level"
        private const val KEY_ENTERPRISE_INSPECTION_ENABLED = "enterprise_inspection_enabled"
        private const val KEY_ENTERPRISE_CONSENT_TS         = "enterprise_consent_ts"
        private const val KEY_ENTERPRISE_SIEM_ENDPOINT      = "enterprise_siem_endpoint"
        private const val KEY_ENTERPRISE_SIEM_API_KEY       = "enterprise_siem_api_key"
        private const val KEY_ENTERPRISE_SHIP_ENABLED       = "enterprise_ship_enabled"
        private const val KEY_ENTERPRISE_LOCAL_LOG_ENABLED  = "enterprise_local_log_enabled"

        // Only for moving an old plaintext SIEM key into SecureSecretStore (see secrets);
        // HTTPS-inspection settings live in the enterprise MitmConfig.
        private const val KEY_MITM_SIEM_API_KEY = "mitm_siem_api_key"

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
        private const val KEY_DUAL_VPN_BLOCK_UDP   = "dual_vpn_block_udp"

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

    // API keys and VPN passwords live in SecureSecretStore (Keystore-wrapped), never
    // in these plain prefs. Values written by older versions are moved on first load.
    private val secrets = SecureSecretStore.getInstance(context).also { store ->
        listOf(
            KEY_ENTERPRISE_SIEM_API_KEY, KEY_MITM_SIEM_API_KEY,
            KEY_DUAL_VPN_HOP1_PASS, KEY_DUAL_VPN_HOP2_PASS,
        ).forEach { key -> store.migratePlaintext(prefs, key, secretName(key)) }
    }

    private fun secretName(prefKey: String) = "settings.$prefKey"
    private fun readSecret(prefKey: String) = secrets.getBackgroundSecret(secretName(prefKey)) ?: ""
    private fun writeSecret(prefKey: String, value: String) = secrets.putBackgroundSecret(secretName(prefKey), value)

    // ==================== EXISTING FLOWS ====================

    private val _notificationsEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    // Off by default: screens with history are FLAG_SECURE in release builds (V-08).
    private val _allowScreenshots = MutableStateFlow(prefs.getBoolean(KEY_ALLOW_SCREENSHOTS, false))
    val allowScreenshots: StateFlow<Boolean> = _allowScreenshots.asStateFlow()

    // Off by default: notifications name no app or host, wherever they are shown (V-09).
    private val _notificationDetails = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATION_DETAILS, false))
    val notificationDetails: StateFlow<Boolean> = _notificationDetails.asStateFlow()

    // On by default, but it only works with exact-alarm access (ProtectionWatchdog),
    // which Android 14+ does not pre-grant: Settings shows it off until granted.
    private val _autoRestart = MutableStateFlow(prefs.getBoolean(KEY_AUTO_RESTART, true))
    val autoRestart: StateFlow<Boolean> = _autoRestart.asStateFlow()

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

    private val _dohEnabled = MutableStateFlow(prefs.getBoolean(KEY_DOH_ENABLED, false))
    val dohEnabled: StateFlow<Boolean> = _dohEnabled.asStateFlow()

    /** Sends the registrable domain of each TLS server the device contacts to crt.sh. */
    private val _ctMonitoringEnabled = MutableStateFlow(prefs.getBoolean(KEY_CT_MONITORING_ENABLED, false))
    val ctMonitoringEnabled: StateFlow<Boolean> = _ctMonitoringEnabled.asStateFlow()

    /** Blocks third-party ad/attribution SDK hosts inside other apps (see AdSdkPolicy). */
    private val _adSdkBlockingEnabled = MutableStateFlow(prefs.getBoolean(KEY_AD_SDK_BLOCKING_ENABLED, true))
    val adSdkBlockingEnabled: StateFlow<Boolean> = _adSdkBlockingEnabled.asStateFlow()

    /**
     * "Block QUIC for better filtering": the user's explicit choice, or null to follow the
     * protection level (on at Strict). Dropped QUIC makes apps use TCP, where SNI is checked.
     */
    private val _blockQuicChoice = MutableStateFlow(if (prefs.contains(KEY_BLOCK_QUIC)) prefs.getBoolean(KEY_BLOCK_QUIC, false) else null)
    val blockQuicChoice: StateFlow<Boolean?> = _blockQuicChoice.asStateFlow()

    /** IDs of "Reduce ad tracking" checklist steps the user marked done. */
    private val _adChecklistDone = MutableStateFlow(prefs.getStringSet(KEY_AD_CHECKLIST_DONE, emptySet()).orEmpty().toSet())
    val adChecklistDone: StateFlow<Set<String>> = _adChecklistDone.asStateFlow()

    /** Networks (NetworkIdentity keys) where the user said no to "switch to Strict". */
    private val _strictSuggestionDismissed = MutableStateFlow(prefs.getStringSet(KEY_STRICT_DISMISSED, emptySet()).orEmpty().toSet())
    val strictSuggestionDismissed: StateFlow<Set<String>> = _strictSuggestionDismissed.asStateFlow()

    /** Rules suggestions the user dismissed; they stay dismissed across restarts. */
    fun dismissedRuleSuggestions(): Set<String> =
        prefs.getStringSet(KEY_RULE_SUGGESTIONS_DISMISSED, emptySet()).orEmpty().toSet()

    fun dismissRuleSuggestion(id: String) {
        prefs.edit().putStringSet(KEY_RULE_SUGGESTIONS_DISMISSED, dismissedRuleSuggestions() + id).apply()
    }

    fun dismissStrictSuggestion(networkKey: String) {
        val next = _strictSuggestionDismissed.value + networkKey
        prefs.edit().putStringSet(KEY_STRICT_DISMISSED, next).apply()
        _strictSuggestionDismissed.value = next
    }

    /** Diagnostics asks api.ipify.org for the public IP. */
    private val _externalIpCheckEnabled = MutableStateFlow(prefs.getBoolean(KEY_EXTERNAL_IP_CHECK_ENABLED, false))
    val externalIpCheckEnabled: StateFlow<Boolean> = _externalIpCheckEnabled.asStateFlow()

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
        readSecret(KEY_ENTERPRISE_SIEM_API_KEY)
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

    // ==================== DUAL VPN FLOWS ====================

    private val _dualVpnEnabled = MutableStateFlow(prefs.getBoolean(KEY_DUAL_VPN_ENABLED, false))
    val dualVpnEnabled: StateFlow<Boolean> = _dualVpnEnabled.asStateFlow()

    private val _dualVpnHop1Host = MutableStateFlow(prefs.getString(KEY_DUAL_VPN_HOP1_HOST, "") ?: "")
    val dualVpnHop1Host: StateFlow<String> = _dualVpnHop1Host.asStateFlow()

    private val _dualVpnHop1Port = MutableStateFlow(prefs.getInt(KEY_DUAL_VPN_HOP1_PORT, 1080))
    val dualVpnHop1Port: StateFlow<Int> = _dualVpnHop1Port.asStateFlow()

    private val _dualVpnHop1User = MutableStateFlow(prefs.getString(KEY_DUAL_VPN_HOP1_USER, "") ?: "")
    val dualVpnHop1User: StateFlow<String> = _dualVpnHop1User.asStateFlow()

    private val _dualVpnHop1Pass = MutableStateFlow(readSecret(KEY_DUAL_VPN_HOP1_PASS))
    val dualVpnHop1Pass: StateFlow<String> = _dualVpnHop1Pass.asStateFlow()

    private val _dualVpnHop2Host = MutableStateFlow(prefs.getString(KEY_DUAL_VPN_HOP2_HOST, "") ?: "")
    val dualVpnHop2Host: StateFlow<String> = _dualVpnHop2Host.asStateFlow()

    private val _dualVpnHop2Port = MutableStateFlow(prefs.getInt(KEY_DUAL_VPN_HOP2_PORT, 1080))
    val dualVpnHop2Port: StateFlow<Int> = _dualVpnHop2Port.asStateFlow()

    private val _dualVpnHop2User = MutableStateFlow(prefs.getString(KEY_DUAL_VPN_HOP2_USER, "") ?: "")
    val dualVpnHop2User: StateFlow<String> = _dualVpnHop2User.asStateFlow()

    private val _dualVpnHop2Pass = MutableStateFlow(readSecret(KEY_DUAL_VPN_HOP2_PASS))
    val dualVpnHop2Pass: StateFlow<String> = _dualVpnHop2Pass.asStateFlow()

    private val _dualVpnBlockUdp = MutableStateFlow(prefs.getBoolean(KEY_DUAL_VPN_BLOCK_UDP, false))
    val dualVpnBlockUdp: StateFlow<Boolean> = _dualVpnBlockUdp.asStateFlow()

    // ==================== EXISTING SETTERS ====================

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
        _notificationsEnabled.value = enabled
    }

    fun setAllowScreenshots(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_SCREENSHOTS, enabled).apply()
        _allowScreenshots.value = enabled
    }

    fun setNotificationDetails(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_DETAILS, enabled).apply()
        _notificationDetails.value = enabled
    }

    fun setAutoRestart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RESTART, enabled).apply()
        _autoRestart.value = enabled
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

    fun setCtMonitoringEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CT_MONITORING_ENABLED, enabled).apply()
        _ctMonitoringEnabled.value = enabled
    }

    /** [enabled] null goes back to the level default. */
    fun setBlockQuic(enabled: Boolean?) {
        prefs.edit().apply { if (enabled == null) remove(KEY_BLOCK_QUIC) else putBoolean(KEY_BLOCK_QUIC, enabled) }.apply()
        _blockQuicChoice.value = enabled
    }

    fun setAdSdkBlockingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AD_SDK_BLOCKING_ENABLED, enabled).apply()
        _adSdkBlockingEnabled.value = enabled
    }

    fun setAdChecklistDone(id: String, done: Boolean) {
        val next = if (done) _adChecklistDone.value + id else _adChecklistDone.value - id
        prefs.edit().putStringSet(KEY_AD_CHECKLIST_DONE, next).apply()
        _adChecklistDone.value = next
    }

    fun setExternalIpCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXTERNAL_IP_CHECK_ENABLED, enabled).apply()
        _externalIpCheckEnabled.value = enabled
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

    val hasVpnConsent: Boolean get() = _vpnConsentTimestampMs.value > 0L

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
        writeSecret(KEY_ENTERPRISE_SIEM_API_KEY, apiKey)
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

    // ==================== DUAL VPN SETTERS ====================

    fun setDualVpnEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DUAL_VPN_ENABLED, enabled).apply()
        _dualVpnEnabled.value = enabled
    }
    fun setDualVpnHop1Host(host: String) { prefs.edit().putString(KEY_DUAL_VPN_HOP1_HOST, host).apply(); _dualVpnHop1Host.value = host }
    fun setDualVpnHop1Port(port: Int)    { prefs.edit().putInt(KEY_DUAL_VPN_HOP1_PORT, port).apply();    _dualVpnHop1Port.value = port }
    fun setDualVpnHop1User(user: String) { prefs.edit().putString(KEY_DUAL_VPN_HOP1_USER, user).apply(); _dualVpnHop1User.value = user }
    fun setDualVpnHop1Pass(pass: String) { writeSecret(KEY_DUAL_VPN_HOP1_PASS, pass); _dualVpnHop1Pass.value = pass }
    fun setDualVpnHop2Host(host: String) { prefs.edit().putString(KEY_DUAL_VPN_HOP2_HOST, host).apply(); _dualVpnHop2Host.value = host }
    fun setDualVpnHop2Port(port: Int)    { prefs.edit().putInt(KEY_DUAL_VPN_HOP2_PORT, port).apply();    _dualVpnHop2Port.value = port }
    fun setDualVpnHop2User(user: String) { prefs.edit().putString(KEY_DUAL_VPN_HOP2_USER, user).apply(); _dualVpnHop2User.value = user }
    fun setDualVpnHop2Pass(pass: String) { writeSecret(KEY_DUAL_VPN_HOP2_PASS, pass); _dualVpnHop2Pass.value = pass }
    fun setDualVpnBlockUdp(block: Boolean) { prefs.edit().putBoolean(KEY_DUAL_VPN_BLOCK_UDP, block).apply(); _dualVpnBlockUdp.value = block }

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
            blockUdpOutsideChain = _dualVpnBlockUdp.value,
        )

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