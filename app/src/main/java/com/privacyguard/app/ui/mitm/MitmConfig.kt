package com.privacyguard.app.ui.mitm

import android.content.Context
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import kotlinx.coroutines.flow.StateFlow

/**
 * Enterprise inspection configuration facade.
 *
 * This wraps [SettingsPreferences] so enterprise-only controls can be managed
 * from a single place without scattering preference keys around the UI.
 *
 * Business reason:
 * PrivacyGuard enterprise builds need explicit, auditable enablement for
 * metadata inspection and consent renewal on managed devices.
 *
 * Thread safety:
 * Delegates to [SettingsPreferences], which exposes thread-safe StateFlows and
 * SharedPreferences-backed setters.
 */
class MitmConfig private constructor(
    private val prefs: SettingsPreferences,
) {
    companion object {
        @Volatile
        private var INSTANCE: MitmConfig? = null

        fun getInstance(context: Context): MitmConfig {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MitmConfig(SettingsPreferences.getInstance(context.applicationContext))
                    .also { INSTANCE = it }
            }
        }

        const val CONSENT_VALIDITY_MS: Long = 90L * 24L * 60L * 60L * 1000L
    }

    val isEnabled: StateFlow<Boolean> = prefs.enterpriseInspectionEnabled
    val consentTimestampMs: StateFlow<Long> = prefs.enterpriseConsentTimestampMs
    val siemEndpoint: StateFlow<String> = prefs.enterpriseSiemEndpoint
    val siemApiKey: StateFlow<String> = prefs.enterpriseSiemApiKey
    val shipToSiem: StateFlow<Boolean> = prefs.enterpriseShipEnabled
    val writeLocalLog: StateFlow<Boolean> = prefs.enterpriseLocalLogEnabled

    fun setEnabled(enabled: Boolean) = prefs.setEnterpriseInspectionEnabled(enabled)
    fun recordConsent(timestampMs: Long) = prefs.setEnterpriseConsentTimestampMs(timestampMs)
    fun setSiemEndpoint(endpoint: String) = prefs.setEnterpriseSiemEndpoint(endpoint)
    fun setSiemApiKey(apiKey: String) = prefs.setEnterpriseSiemApiKey(apiKey)
    fun setShipToSiem(enabled: Boolean) = prefs.setEnterpriseShipEnabled(enabled)
    fun setWriteLocalLog(enabled: Boolean) = prefs.setEnterpriseLocalLogEnabled(enabled)

    fun isConsentFresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        val recorded = consentTimestampMs.value
        return recorded > 0L && nowMs - recorded <= CONSENT_VALIDITY_MS
    }
}
