package com.privacyguard.app.core.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Android's Private DNS in hostname ("strict") mode. CoreLock drops DNS over TLS so
 * apps use the DNS it filters; in "Automatic" mode Android then falls back to plain
 * DNS, but with a hostname set it does not, and every lookup fails while the VPN runs.
 */
object PrivateDnsCheck {

    private const val TAG = "PrivateDnsCheck"

    /** Android's Private DNS setting, as Diagnostics shows it. */
    enum class Mode { OFF, AUTOMATIC, HOSTNAME, OFF_OR_AUTOMATIC }

    /**
     * The mode from Android's setting (`private_dns_mode`: off, opportunistic, hostname),
     * or, when it can't be read, from the real network. `isPrivateDnsActive` alone is not
     * the setting: in Automatic it is true only when DNS over TLS works on that network,
     * so Automatic with a resolver without DoT looked "Off". Off and Automatic without
     * DoT look the same on the network, so without the setting it says both.
     */
    fun mode(setting: String?, hostnameOnLink: Boolean, activeOnLink: Boolean): Mode = when {
        hostnameOnLink -> Mode.HOSTNAME
        else -> when (setting?.trim()?.lowercase(java.util.Locale.ROOT)) {
            "hostname" -> Mode.HOSTNAME
            "opportunistic" -> Mode.AUTOMATIC
            "off" -> Mode.OFF
            else -> if (activeOnLink) Mode.AUTOMATIC else Mode.OFF_OR_AUTOMATIC
        }
    }

    /**
     * Android's `Settings.Global.PRIVATE_DNS_MODE` (hidden, readable by apps), or null when
     * unset or unreadable. Unset means Android's default, which an OEM may change, so it is
     * not guessed here. [key]: also used for `private_dns_specifier`, the hostname.
     */
    fun setting(context: Context, key: String = "private_dns_mode"): String? =
        runCatching { Settings.Global.getString(context.contentResolver, key) }
            .onFailure { Log.w(TAG, "Private DNS setting not readable: ${it.javaClass.simpleName}") }
            .getOrNull()?.takeIf { it.isNotBlank() }

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
     * Emits when the Private DNS setting may have changed, while collected. Android applies
     * the setting to each real network's LinkProperties, so a change there is the moment
     * [strictHostname] can read the new value; a screen that only re-checks on resume
     * stays wrong while it is visible (the "Start anyway" dialog case). Only real networks:
     * a NetworkRequest excludes VPNs by default, and the VPN (us) carries no Private DNS.
     */
    fun changes(context: Context): Flow<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyFlow()
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyFlow()
        return callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { trySend(Unit) }
                override fun onLost(network: Network) { trySend(Unit) }
            }
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            val registered = runCatching { cm.registerNetworkCallback(request, callback) }
                // Too many callbacks for this app (Android allows 100): resume still re-checks.
                .onFailure { Log.w(TAG, "Private DNS watch not registered: ${it.javaClass.simpleName}") }
                .isSuccess
            awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
        }
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
