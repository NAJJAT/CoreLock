package com.privacyguard.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.privacyguard.vpn.mitm.CaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Device / Profile Owner receiver for enterprise MDM deployment.
 *
 * When the EMM promotes this app to Device Owner or Profile Owner, Android calls
 * [onEnabled] / [onProfileProvisioningComplete]. We use that moment to:
 *   1. Install the PrivacyGuard CA into the device trust store automatically.
 *   2. Apply any managed configuration that the EMM has already pushed.
 *
 * For consumer devices (not MDM-managed), this receiver is still registered so that
 * if an IT admin manually enables Device Admin, the same flow triggers.
 *
 * CA installation via [DevicePolicyManager.installCaCert] requires the app to be
 * Device Owner OR Profile Owner. Regular Device Admin status is NOT sufficient.
 */
class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {

    companion object {
        private const val TAG = "PGDeviceAdmin"

        fun componentName(context: Context) =
            ComponentName(context.packageName, DeviceAdminReceiver::class.java.name)

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }

        fun isProfileOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isProfileOwnerApp(context.packageName)
        }

        fun hasOwnerPrivileges(context: Context) = isDeviceOwner(context) || isProfileOwner(context)

        /**
         * Installs the PrivacyGuard CA into the system trust store.
         *
         * Requires the app to be Device Owner or Profile Owner — call
         * [hasOwnerPrivileges] before this.  Returns true on success.
         *
         * The installed CA is trusted by all apps on the device (Device Owner)
         * or all apps inside the work profile (Profile Owner).
         */
        fun installCaCertificate(context: Context): Boolean {
            if (!hasOwnerPrivileges(context)) {
                Log.w(TAG, "installCaCert: app is not Device/Profile Owner — skipping")
                return false
            }
            return try {
                val caManager = CaManager(context)
                val cert = caManager.getCaCert() ?: run {
                    Log.e(TAG, "installCaCert: CA not yet generated (call initialize() first)")
                    return false
                }
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                        as DevicePolicyManager
                val installed = dpm.installCaCert(componentName(context), cert.encoded)
                Log.i(TAG, "installCaCert: result=$installed")
                installed
            } catch (e: Exception) {
                Log.e(TAG, "installCaCert failed", e)
                false
            }
        }
    }

    // Background scope — never block the main thread inside a BroadcastReceiver
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Lifecycle callbacks ───────────────────────────────────────────────────

    /** Called when Device Admin is activated (user tapped Activate on the prompt). */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled  deviceOwner=${isDeviceOwner(context)}")
        if (hasOwnerPrivileges(context)) {
            installCaAndApplyConfig(context)
        }
    }

    /** Called when the app is removed as Device Admin. */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device admin disabled")
    }

    /**
     * Called after zero-touch / QR / NFC enrollment completes and the app
     * becomes Profile Owner of a fresh work profile.  This is the main entry
     * point for fully automated enterprise deployments.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Work profile provisioning complete — installing CA and applying config")
        installCaAndApplyConfig(context)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────
    // Note: APPLICATION_RESTRICTIONS_CHANGED is handled by MdmConfigReceiver,
    // not here — see AndroidManifest.xml for the reason.

    private fun installCaAndApplyConfig(context: Context) {
        scope.launch {
            // 1. Generate the CA key pair if this is the first run.
            val caManager = CaManager(context)
            val ok = caManager.initialize()
            Log.d(TAG, "CaManager.initialize()=$ok")

            // 2. Install the CA into the system / profile trust store.
            if (ok) {
                val installed = installCaCertificate(context)
                Log.i(TAG, "CA installation result: $installed")
            }

            // 3. Apply any managed configuration already pushed by the EMM.
            ManagedConfigApplier.apply(context)
        }
    }
}
