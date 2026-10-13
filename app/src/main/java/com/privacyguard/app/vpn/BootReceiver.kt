package com.privacyguard.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts the VPN service after device reboot if it was active before shutdown (FR-VPN-10).
 *
 * Only BOOT_COMPLETED, a protected broadcast that only the system can send. The
 * old HTC-style QUICKBOOT_POWERON action is not protected, so any app could send
 * it to this exported receiver.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!prefs(context).getBoolean(KEY_VPN_WAS_ACTIVE, false)) return

        // Another VPN app took the permission since: starting would only fail.
        if (VpnService.prepare(context) != null) {
            Log.w("BootReceiver", "VPN permission no longer granted; not restarting")
            markVpnStopped(context)
            return
        }
        // BOOT_COMPLETED may start a foreground service from the background; the
        // service calls startForeground() as its first step.
        ContextCompat.startForegroundService(
            context,
            com.privacyguard.platform.android.PrivacyVpnService.startIntent(context),
        )
    }

    companion object {
        private const val PREFS_NAME     = "privacyguard_boot"
        private const val KEY_VPN_WAS_ACTIVE = "vpn_was_active"

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** Call once the VPN is actually up. */
        fun markVpnActive(context: Context) =
            prefs(context).edit().putBoolean(KEY_VPN_WAS_ACTIVE, true).apply()

        /** Call when VPN stops intentionally, or can no longer start. */
        fun markVpnStopped(context: Context) =
            prefs(context).edit().putBoolean(KEY_VPN_WAS_ACTIVE, false).apply()
    }
}
