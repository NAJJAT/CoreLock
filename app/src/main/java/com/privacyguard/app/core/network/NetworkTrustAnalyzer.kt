package com.privacyguard.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsAnomalyEntity

data class NetworkTrustSummary(
    val networkKey: String,
    val networkLabel: String,
    val score: Int,
    val level: String,
    val shouldRecommendStrict: Boolean,
    val reasons: List<String>,
    val cleartextCount: Int,
    val dnsAnomalyCount: Int,
    val weakTlsCount: Int,
    val blockedCount: Int,
)

object NetworkTrustAnalyzer {
    fun summarize(
        context: Context,
        connections: List<ConnectionEntity>,
        anomalies: List<DnsAnomalyEntity>,
    ): NetworkTrustSummary {
        val (networkKey, networkLabel, isWifi) = detectNetwork(context)
        var penalty = 0
        val reasons = mutableListOf<String>()

        val cleartext = connections.count { it.encryptionStatus == "CLEARTEXT" || it.encryptionStatus == "UNKNOWN" }
        if (cleartext > 0) {
            penalty += minOf(25, cleartext * 3)
            reasons += "$cleartext cleartext or unknown sessions"
        }

        val rogueDns = anomalies.count { it.anomalyType.contains("DNS", ignoreCase = true) || it.anomalyType.contains("DGA", ignoreCase = true) }
        if (rogueDns > 0) {
            penalty += minOf(30, rogueDns * 5)
            reasons += "$rogueDns DNS anomalies"
        }

        val mitmSignals = connections.count { it.encryptionStatus == "WEAK_TLS" } + anomalies.count { it.anomalyType.contains("WEAK", ignoreCase = true) }
        if (mitmSignals > 0) {
            penalty += minOf(25, mitmSignals * 5)
            reasons += "$mitmSignals weak encryption signals"
        }

        val blocked = connections.count { it.wasBlocked }
        if (blocked > 0) {
            penalty += minOf(15, blocked / 3)
            reasons += "$blocked blocked attempts"
        }

        val score = (100 - penalty).coerceIn(0, 100)
        val level = when {
            score >= 80 -> "Trusted"
            score >= 55 -> "Watch"
            else -> "Reduced"
        }

        return NetworkTrustSummary(
            networkKey = networkKey,
            networkLabel = networkLabel,
            score = score,
            level = level,
            shouldRecommendStrict = level == "Reduced" && isWifi,
            reasons = if (reasons.isEmpty()) listOf("No cleartext, DNS, or weak TLS issues seen") else reasons.take(3),
            cleartextCount = cleartext,
            dnsAnomalyCount = rogueDns,
            weakTlsCount = mitmSignals,
            blockedCount = blocked,
        )
    }

    /**
     * Key, display label and whether it is Wi-Fi. The key keeps the original
     * English-derived format so networks remembered before localization still
     * match, and never changes with the UI language.
     */
    private fun detectNetwork(context: Context): Triple<String, String, Boolean> {
        fun s(id: Int) = context.getString(id)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Triple("offline", s(com.privacyguard.app.R.string.net_offline), false)
        val network = cm.activeNetwork ?: return Triple("offline", s(com.privacyguard.app.R.string.net_offline), false)
        val caps = cm.getNetworkCapabilities(network)
            ?: return Triple("unknown network", s(com.privacyguard.app.R.string.net_unknown), false)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> currentSsid(context)
                ?.let { ssid -> Triple("wi-fi: ${ssid.lowercase()}", context.getString(com.privacyguard.app.R.string.net_wifi_named, ssid), true) }
                ?: Triple("wi-fi network", s(com.privacyguard.app.R.string.net_wifi), true)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Triple("cellular network", s(com.privacyguard.app.R.string.net_cellular), false)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Triple("ethernet network", s(com.privacyguard.app.R.string.net_ethernet), false)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Triple("vpn network", s(com.privacyguard.app.R.string.net_vpn), false)
            else -> Triple("unknown network", s(com.privacyguard.app.R.string.net_unknown), false)
        }
    }

    private fun currentSsid(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        return runCatching { wifiManager.connectionInfo?.ssid }.getOrNull()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }
}