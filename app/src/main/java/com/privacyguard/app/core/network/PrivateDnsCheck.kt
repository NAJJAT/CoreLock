package com.privacyguard.app.core.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings

/**
 * Android's Private DNS in hostname ("strict") mode. CoreLock drops DNS over TLS so
 * apps use the DNS it filters; in "Automatic" mode Android then falls back to plain
 * DNS, but with a hostname set it does not, and every lookup fails while the VPN runs.
 */
object PrivateDnsCheck {

    /**
     * The Private DNS hostname set on the device's real network (not the VPN), or null
     * when Private DNS is off or Automatic, or below Android 9 (no Private DNS).
     */
    fun strictHostname(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            // The VPN is the default network while it runs, so look at the underlying ones.
            @Suppress("DEPRECATION")
            cm.allNetworks.firstNotNullOfOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@firstNotNullOfOrNull null
                // Set only in hostname mode (Automatic leaves it null, even when DoT is active).
                cm.getLinkProperties(network)?.privateDnsServerName?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /**
     * Opens the screen nearest to Private DNS. Android has no public action for the
     * Private DNS dialog itself; it sits under network / connection settings.
     */
    fun openSettings(context: Context) {
        for (action in listOf(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)) {
            val opened = runCatching {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }
    }
}
