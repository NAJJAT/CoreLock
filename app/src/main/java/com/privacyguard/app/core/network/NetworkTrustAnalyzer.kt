package com.privacyguard.app.core.network

import android.content.Context
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

    /** Trust of the network the phone is on now, from what was seen on that network only. */
    fun summarize(
        context: Context,
        connections: List<ConnectionEntity>,
        anomalies: List<DnsAnomalyEntity>,
        blockedAttempts: List<ConnectionEntity> = emptyList(),
    ): NetworkTrustSummary = score(NetworkIdentity.current(context), connections, anomalies, blockedAttempts)

    /**
     * Rows recorded on another network, or before rows carried a network (null), are
     * ignored: traffic from the mobile network says nothing about this Wi-Fi.
     * Only confirmed plain HTTP counts as cleartext; "unknown" (QUIC, chat protocols,
     * handshakes we did not see) is not evidence of anything.
     */
    fun score(
        network: NetworkId,
        allConnections: List<ConnectionEntity>,
        allAnomalies: List<DnsAnomalyEntity>,
        /** Connection attempts PrivacyGuard dropped (wasBlocked rows). */
        allBlockedAttempts: List<ConnectionEntity> = emptyList(),
    ): NetworkTrustSummary {
        val connections = allConnections.filter { it.networkKey == network.key && !it.wasBlocked }
        val anomalies = allAnomalies.filter { it.networkKey == network.key }
        var penalty = 0
        val reasons = mutableListOf<String>()

        // Plain HTTP to devices on this network (router, Chromecast) is not a trust signal.
        val cleartext = connections.count { com.privacyguard.app.ui.traffic.HomeEvidence.isInternetCleartext(it) }
        if (cleartext > 0) {
            penalty += minOf(25, cleartext * 3)
            reasons += "$cleartext cleartext sessions"
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

        // Blocked rows may arrive in either list (callers pass each row once).
        val blocked = (allBlockedAttempts + allConnections.filter { it.wasBlocked })
            .count { it.networkKey == network.key }
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
            networkKey = network.key,
            networkLabel = network.label,
            score = score,
            level = level,
            shouldRecommendStrict = level == "Reduced" && network.isWifi,
            reasons = if (reasons.isEmpty()) listOf("No cleartext, DNS, or weak TLS issues seen") else reasons.take(3),
            cleartextCount = cleartext,
            dnsAnomalyCount = rogueDns,
            weakTlsCount = mitmSignals,
            blockedCount = blocked,
        )
    }
}
