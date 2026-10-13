package com.privacyguard.app.core.network

import android.content.Context
import com.privacyguard.app.data.db.DnsAnomalyEntity

data class NetworkTrustSummary(
    val networkKey: String,
    val networkLabel: String,
    val score: Int,
    val level: String,
    val shouldRecommendStrict: Boolean,
    val reasons: List<String>,
    val openWifi: Boolean,
    val captivePortal: Boolean,
    /** DNS answers that pointed a public name at a private or null address. */
    val tamperedAnswers: Int,
    /** The network answered a name that cannot exist: it rewrites or answers DNS itself. */
    val dnsHijacked: Boolean,
    /** A certificate on our own TLS connection was swapped on the way. */
    val tlsIntercepted: Boolean,
)

/**
 * Trust of the network the phone is on, from signs about the network itself only.
 * What apps do (plain HTTP, old TLS, trackers, our own blocking) says nothing about
 * the Wi-Fi they use, and lives in app risk instead.
 *
 * Penalties (score starts at 100):
 *  - TLS interception detected            −40  someone can read and change HTTPS
 *  - DNS hijacking (fake answer to a name that cannot exist)  −30
 *  - Open, unencrypted Wi-Fi              −30  anyone nearby can see your traffic
 *  - DNS answers that look tampered       −10 each, at most −30
 *  - Captive portal (sign-in page)        −15  the network operator sits in the path
 * Levels: 80+ Trusted, 55–79 Watch, below 55 Reduced (then Strict is suggested on Wi-Fi).
 */
object NetworkTrustAnalyzer {

    const val OPEN_WIFI_PENALTY = 30
    const val CAPTIVE_PENALTY = 15
    const val TAMPER_PENALTY_EACH = 10
    const val TAMPER_PENALTY_MAX = 30
    const val HIJACK_PENALTY = 30
    const val INTERCEPTION_PENALTY = 40

    fun summarize(context: Context, anomalies: List<DnsAnomalyEntity>): NetworkTrustSummary =
        score(NetworkIdentity.current(context), anomalies)

    /** Findings recorded on another network, or before rows carried one, are ignored. */
    fun score(network: NetworkId, allAnomalies: List<DnsAnomalyEntity>): NetworkTrustSummary {
        val here = allAnomalies.filter { it.networkKey == network.key }
        val tampered = here.count { it.anomalyType == NetworkSignals.DNS_TAMPERED }
        val hijacked = here.any { it.anomalyType == NetworkSignals.DNS_HIJACK }
        val intercepted = here.any { it.anomalyType == NetworkSignals.TLS_INTERCEPTION }
        val open = network.openWifi == true

        var penalty = 0
        val reasons = mutableListOf<String>()
        if (intercepted) { penalty += INTERCEPTION_PENALTY; reasons += "TLS interception detected" }
        if (hijacked) { penalty += HIJACK_PENALTY; reasons += "DNS answers are rewritten" }
        if (open) { penalty += OPEN_WIFI_PENALTY; reasons += "Open Wi-Fi without encryption" }
        if (tampered > 0) {
            penalty += minOf(TAMPER_PENALTY_MAX, tampered * TAMPER_PENALTY_EACH)
            reasons += "$tampered DNS answers look tampered"
        }
        if (network.captivePortal) { penalty += CAPTIVE_PENALTY; reasons += "Captive portal" }

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
            reasons = reasons.ifEmpty { listOf("No signs of an untrustworthy network") },
            openWifi = open,
            captivePortal = network.captivePortal,
            tamperedAnswers = tampered,
            dnsHijacked = hijacked,
            tlsIntercepted = intercepted,
        )
    }
}
