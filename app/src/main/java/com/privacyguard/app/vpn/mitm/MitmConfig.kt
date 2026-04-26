package com.privacyguard.vpn.mitm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MITM Configuration Manager
 *
 * Manages runtime settings for the MITM feature, backed by SharedPreferences
 * for persistent storage.
 */
class MitmConfig(
    private val context: Context
) {

    companion object {
        private const val PREFS_NAME = "mitm_prefs"

        // Default values
        private const val DEFAULT_MAX_PAYLOAD_SIZE = 32768 // 32KB
        private const val DEFAULT_RETENTION_DAYS = 7
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // StateFlows for UI observation
    private val _isEnabled = MutableStateFlow(prefs.getBoolean("mitm_enabled", false))
    val isEnabledFlow: Flow<Boolean> = _isEnabled.asStateFlow()

    private val _shipToSiem = MutableStateFlow(prefs.getBoolean("mitm_ship_to_siem", false))
    val shipToSiemFlow: Flow<Boolean> = _shipToSiem.asStateFlow()

    /**
     * Get current enabled state (synchronous, for use in VPN packet processing)
     */
    val isEnabled: Boolean
        get() = prefs.getBoolean("mitm_enabled", false)

    val skipPinnedApps: Boolean
        get() = prefs.getBoolean("mitm_skip_pinned_apps", true)

    val redactPii: Boolean
        get() = prefs.getBoolean("mitm_redact_pii", true)

    val siemEndpoint: String
        get() = prefs.getString("mitm_siem_endpoint", "") ?: ""

    val siemApiKey: String
        get() = prefs.getString("mitm_siem_api_key", "") ?: ""

    val maxPayloadSizeBytes: Int
        get() = prefs.getInt("mitm_max_payload_size", DEFAULT_MAX_PAYLOAD_SIZE)

    val shipToSiem: Boolean
        get() = prefs.getBoolean("mitm_ship_to_siem", false)

    val writeLocalLog: Boolean
        get() = prefs.getBoolean("mitm_write_local_log", true)

    val retentionDays: Int
        get() = prefs.getInt("mitm_retention_days", DEFAULT_RETENTION_DAYS)

    val consentTimestamp: Long
        get() = prefs.getLong("mitm_consent_timestamp", 0L)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("mitm_enabled", enabled).apply()
        _isEnabled.value = enabled
    }

    fun setSkipPinnedApps(skip: Boolean) {
        prefs.edit().putBoolean("mitm_skip_pinned_apps", skip).apply()
    }

    fun setRedactPii(redact: Boolean) {
        prefs.edit().putBoolean("mitm_redact_pii", redact).apply()
    }

    fun setSiemEndpoint(endpoint: String) {
        prefs.edit().putString("mitm_siem_endpoint", endpoint).apply()
    }

    fun setSiemApiKey(apiKey: String) {
        prefs.edit().putString("mitm_siem_api_key", apiKey).apply()
    }

    fun setMaxPayloadSize(size: Int) {
        val clampedSize = size.coerceIn(1024, 1024 * 1024)
        prefs.edit().putInt("mitm_max_payload_size", clampedSize).apply()
    }

    fun setShipToSiem(ship: Boolean) {
        prefs.edit().putBoolean("mitm_ship_to_siem", ship).apply()
        _shipToSiem.value = ship
    }

    fun setWriteLocalLog(write: Boolean) {
        prefs.edit().putBoolean("mitm_write_local_log", write).apply()
    }

    fun setRetentionDays(days: Int) {
        val clampedDays = days.coerceIn(1, 90)
        prefs.edit().putInt("mitm_retention_days", clampedDays).apply()
    }

    fun recordConsent() {
        prefs.edit().putLong("mitm_consent_timestamp", System.currentTimeMillis()).apply()
    }

    fun isConsentValid(): Boolean {
        val timestamp = consentTimestamp
        if (timestamp == 0L) return false
        val ninetyDaysAgo = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
        return timestamp > ninetyDaysAgo
    }
}