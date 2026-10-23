package com.privacyguard.mdm

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import android.util.Log
import com.privacyguard.app.MainActivity
import com.privacyguard.platform.android.NotificationHelper
import com.privacyguard.vpn.interception.InspectionStatus
import com.privacyguard.vpn.interception.TlsInterceptionFactory
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
 * FlavorStartup does both: it applies at startup and registers [MdmConfigReceiver].
 *
 * Supported keys (all optional — missing keys leave the current setting unchanged):
 *
 *  mitm_enabled          boolean  Enable HTTPS payload inspection
 *  mitm_consent_given    boolean  EMM pre-confirms legal consent on behalf of the org
 *  ca_install_automatic  boolean  Install CA cert silently when app is Device/Profile Owner
 *  mitm_siem_endpoint    string   URL of the SIEM/log aggregation server
 *  mitm_siem_api_key     string   Bearer token for the SIEM endpoint
 *  mitm_redact_pii       boolean  Redact PII before storing/shipping payloads
 *  mitm_retention_days   integer  How long to keep payload logs (1-30)
 */
object ManagedConfigApplier {

    private const val TAG = "ManagedConfig"

    fun apply(context: Context) {
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        val bundle: Bundle = rm.applicationRestrictions

        if (bundle.isEmpty) {
            Log.d(TAG, "No managed configuration present (unmanaged device)")
            withdrawOrganisationSettings(MitmConfig(context))
            return
        }

        Log.i(TAG, "Applying managed configuration — keys: ${bundle.keySet()}")
        val mitmConfig = MitmConfig(context)
        val statusBefore = TlsInterceptionFactory.status(context)

        applyBoolean(bundle, "mitm_enabled") { enabled ->
            if (enabled) mitmConfig.setEnabled(true, byOrganisation = true) else mitmConfig.setEnabled(false)
        }

        // Organisation-level consent is valid for as long as the policy asserts it
        // (MitmConfig.consentValid): it is recorded once, not refreshed on every app
        // start, and withdrawn when the policy switches it off or disappears, so an
        // administrator can revoke it remotely.
        applyBoolean(bundle, "mitm_consent_given") { given ->
            if (given && !(mitmConfig.consentByOrganisation && mitmConfig.isConsentValid())) {
                mitmConfig.recordConsent(byOrganisation = true)
                Log.i(TAG, "Enterprise consent recorded by MDM policy")
            } else if (!given && mitmConfig.consentTimestamp != 0L) {
                mitmConfig.clearConsent()
                Log.i(TAG, "Enterprise consent withdrawn by MDM policy")
            }
        }

        applyString(bundle, "mitm_siem_endpoint") { mitmConfig.setSiemEndpoint(it) }
        applyString(bundle, "mitm_siem_api_key")  { mitmConfig.setSiemApiKey(it) }
        applyString(bundle, "mitm_siem_signing_key") { mitmConfig.setSiemSigningKey(it) }
        applyString(bundle, "mitm_siem_pin_sha256")  { mitmConfig.setSiemPinSha256(it) }

        applyBoolean(bundle, "mitm_redact_pii")  { mitmConfig.setRedactPii(it) }
        applyBoolean(bundle, "mitm_ship_to_siem") { mitmConfig.setShipToSiem(it) }

        applyInt(bundle, "mitm_retention_days") {
            mitmConfig.setRetentionDays(it)
        }

        // If the EMM pushes ca_install_automatic=true AND the app is Device/Profile
        // Owner, install the CA silently right now, but only while inspection is on and
        // consented to: otherwise a CA would exist (and be trusted) with nothing using it.
        applyBoolean(bundle, "ca_install_automatic") { autoInstall ->
            val active = TlsInterceptionFactory.isInspectionActive(mitmConfig.isEnabled, mitmConfig.isConsentValid())
            if (autoInstall && active && DeviceAdminReceiver.hasOwnerPrivileges(context)) {
                // The CA is created lazily; without this, the install at app start
                // always failed with "CA not yet generated". apply() only runs on IO
                // threads (FlavorStartup, MdmConfigReceiver, DeviceAdminReceiver).
                kotlinx.coroutines.runBlocking { com.privacyguard.vpn.mitm.CaManager(context).initialize() }
                val installed = DeviceAdminReceiver.installCaCertificate(context)
                Log.i(TAG, "MDM automatic CA install: $installed")
            }
        }

        // The user is told once when their organisation starts inspecting; afterwards the
        // VPN notification and the dashboard keep saying so.
        if (statusBefore != InspectionStatus.ON_BY_ORGANISATION &&
            TlsInterceptionFactory.status(context) == InspectionStatus.ON_BY_ORGANISATION) {
            runCatching { NotificationHelper(context).postInspectionEnabledByOrganisation(MainActivity::class.java) }
                .onFailure { Log.w(TAG, "Inspection notice not posted: ${it.javaClass.simpleName}") }
        }

        Log.i(TAG, "Managed configuration applied successfully")
    }

    /**
     * The phone is no longer managed (or the policy was removed): what the organisation
     * switched on or consented to ends with it. The user's own choices stay.
     */
    internal fun withdrawOrganisationSettings(mitmConfig: MitmConfig) {
        if (mitmConfig.enabledByOrganisation) {
            mitmConfig.setEnabled(false)
            Log.i(TAG, "Inspection enabled by a removed policy: switched off")
        }
        if (mitmConfig.consentByOrganisation) {
            mitmConfig.clearConsent()
            Log.i(TAG, "Consent given by a removed policy: withdrawn")
        }
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
