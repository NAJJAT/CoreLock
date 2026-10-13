package com.privacyguard.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.privacyguard.app.R

/** The physical network traffic leaves through: what "this network" means for trust. */
data class NetworkId(
    /** Stable, language-independent key; stored on each connection and anomaly. */
    val key: String,
    val label: String,
    val isWifi: Boolean,
)

object NetworkIdentity {

    private const val CACHE_MS = 5_000L

    @Volatile private var cached: NetworkId? = null
    @Volatile private var cachedAt = 0L

    /**
     * Called for every logged connection, so it is cached for a few seconds instead
     * of asking ConnectivityManager each time.
     */
    fun current(context: Context): NetworkId {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_MS) return it }
        return detect(context.applicationContext).also { cached = it; cachedAt = now }
    }

    /**
     * While our VPN runs, the active network is the VPN itself; look past it to the
     * network underneath. The key keeps the format used before localization
     * ("wi-fi: <ssid>", "cellular network", ...) so remembered networks still match.
     * Without location permission Android hides the SSID, and every Wi-Fi network
     * then shares the key "wi-fi network".
     */
    private fun detect(context: Context): NetworkId {
        fun s(id: Int) = context.getString(id)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkId("offline", s(R.string.net_offline), false)

        @Suppress("DEPRECATION")
        val underlying = cm.allNetworks
            .mapNotNull { n -> cm.getNetworkCapabilities(n) }
            .filter {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .sortedWith(
                compareByDescending<NetworkCapabilities> { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                    .thenBy { rank(it) }
            )
            .firstOrNull()
            ?: return NetworkId("offline", s(R.string.net_offline), false)

        return when {
            underlying.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ssid(context, underlying)
                ?.let { NetworkId("wi-fi: ${it.lowercase()}", context.getString(R.string.net_wifi_named, it), true) }
                ?: NetworkId("wi-fi network", s(R.string.net_wifi), true)
            underlying.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkId("cellular network", s(R.string.net_cellular), false)
            underlying.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkId("ethernet network", s(R.string.net_ethernet), false)
            else -> NetworkId("unknown network", s(R.string.net_unknown), false)
        }
    }

    private fun rank(caps: NetworkCapabilities): Int = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
        else -> 3
    }

    private fun ssid(context: Context, caps: NetworkCapabilities): String? {
        val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (caps.transportInfo as? WifiInfo)?.ssid
        } else null
        @Suppress("DEPRECATION")
        val raw = fromCaps ?: runCatching {
            (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid
        }.getOrNull()
        return raw?.trim('"')?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
    }
}
