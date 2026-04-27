package com.privacyguard.mdm

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import android.util.Log
import com.privacyguard.vpn.mitm.MitmConfig

/**
 * Reads the managed configuration bundle pushed by the EMM/MDM and applies it
 * to the app's runtime settings.
 *
 * EMMs write configuration via the standard Android AppConfig / Managed
 * Configurations mechanism ([RestrictionsManager.getApplicationRestrictions]).
 * The schema is declared in res/xml/application_restrictions.xml, which EMM
 * consoles read to build their policy UI.
 *
 * Call [apply] once at startup and whenever the system broadcasts
 * [android.content.Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED].
 * [DeviceAdminReceiver] handles the broadcast automatically.
 *
 * Supported keys (all optional — missing keys leave the current setting unchanged):
 *
 *  mitm_enabled          boolean  Enable HTTPS payload inspection
 *  mitm_consent_given    boolean  EMM pre-confirms legal consent on behalf of the org
 *  ca_install_automatic  boolean  Install CA cert silently when app is Device/Profile Owner
 *  mitm_siem_endpoint    string   URL of the SIEM/log aggregation server
 *  mitm_siem_api_key     string   Bearer token for the SIEM endpoint
 *  mitm_redact_pii       boolean  Redact PII before storing/shipping payloads
 *  mitm_retention_days   integer  How long to keep payload logs (1-90)
 */
object ManagedConfigApplier {

    private const val TAG = "ManagedConfig"

    fun apply(context: Context) {
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        val bundle: Bundle = rm.applicationRestrictions

        if (bundle.isEmpty) {
            Log.d(TAG, "No managed configuration present (consumer / unmanaged device)")
            return
        }

        Log.i(TAG, "Applying managed configuration — keys: ${bundle.keySet()}")
        val mitmConfig = MitmConfig(context)

        applyBoolean(bundle, "mitm_enabled") { mitmConfig.setEnabled(it) }

        applyBoolean(bundle, "mitm_consent_given") { given ->
            if (given) {
                mitmConfig.recordConsent()
                Log.i(TAG, "Enterprise consent recorded by MDM policy")
            }
        }

        applyString(bundle, "mitm_siem_endpoint") { mitmConfig.setSiemEndpoint(it) }
        applyString(bundle, "mitm_siem_api_key")  { mitmConfig.setSiemApiKey(it) }

        applyBoolean(bundle, "mitm_redact_pii")  { mitmConfig.setRedactPii(it) }
        applyBoolean(bundle, "mitm_ship_to_siem") { mitmConfig.setShipToSiem(it) }

        applyInt(bundle, "mitm_retention_days") {
            mitmConfig.setRetentionDays(it.coerceIn(1, 90))
        }

        // If the EMM pushes ca_install_automatic=true AND the app is Device/Profile
        // Owner, install the CA silently right now.
        applyBoolean(bundle, "ca_install_automatic") { autoInstall ->
            if (autoInstall && DeviceAdminReceiver.hasOwnerPrivileges(context)) {
                val installed = DeviceAdminReceiver.installCaCertificate(context)
                Log.i(TAG, "MDM automatic CA install: $installed")
            }
        }

        Log.i(TAG, "Managed configuration applied successfully")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun applyBoolean(bundle: Bundle, key: String, action: (Boolean) -> Unit) {
        if (bundle.containsKey(key)) {
            val value = bundle.getBoolean(key)
            Log.d(TAG, "  $key = $value")
            action(value)
        }
    }

    private fun applyString(bundle: Bundle, key: String, action: (String) -> Unit) {
        if (bundle.containsKey(key)) {
            val value = bundle.getString(key, "") ?: ""
            Log.d(TAG, "  $key = [${value.length} chars]")
            action(value)
        }
    }

    private fun applyInt(bundle: Bundle, key: String, action: (Int) -> Unit) {
        if (bundle.containsKey(key)) {
            val value = bundle.getInt(key)
            Log.d(TAG, "  $key = $value")
            action(value)
        }
    }
}
