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
        val networkLabel = detectNetworkLabel(context)
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
            networkKey = networkLabel.lowercase(),
            networkLabel = networkLabel,
            score = score,
            level = level,
            shouldRecommendStrict = level == "Reduced" && networkLabel.contains("Wi", ignoreCase = true),
            reasons = if (reasons.isEmpty()) listOf("No cleartext, DNS, or weak TLS issues seen") else reasons.take(3),
            cleartextCount = cleartext,
            dnsAnomalyCount = rogueDns,
            weakTlsCount = mitmSignals,
            blockedCount = blocked,
        )
    }

    private fun detectNetworkLabel(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "Offline"
        val network = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "Unknown network"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> currentSsid(context) ?: "Wi-Fi network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN network"
            else -> "Unknown network"
        }
    }

    private fun currentSsid(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ssid = runCatching { wifiManager.connectionInfo?.ssid }.getOrNull()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        return ssid?.let { "Wi-Fi: $it" }
    }
}
