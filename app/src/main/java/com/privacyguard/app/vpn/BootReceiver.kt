package com.privacyguard.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * Restarts the VPN service after device reboot if it was active before shutdown (FR-VPN-10).
 *
 * Android starts this receiver on BOOT_COMPLETED. We check the persisted VPN-active flag
 * (written when the VPN starts/stops) and restart the real VPN service if needed.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        if (prefs(context).getBoolean(KEY_VPN_WAS_ACTIVE, false)) {
            val startIntent = com.privacyguard.platform.android.PrivacyVpnService.startIntent(context)
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startService(startIntent)
        }
    }

    companion object {
        private const val PREFS_NAME     = "privacyguard_boot"
        private const val KEY_VPN_WAS_ACTIVE = "vpn_was_active"

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** Call when VPN starts. */
        fun markVpnActive(context: Context) =
            prefs(context).edit().putBoolean(KEY_VPN_WAS_ACTIVE, true).apply()

        /** Call when VPN stops intentionally. */
        fun markVpnStopped(context: Context) =
            prefs(context).edit().putBoolean(KEY_VPN_WAS_ACTIVE, false).apply()
    }
}
