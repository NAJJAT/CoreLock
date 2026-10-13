package com.privacyguard.app.core.network

import com.privacyguard.core.packet.DnsPacket

/**
 * Signs that the network itself (not an app) is untrustworthy. Each rule is pure so
 * it can be tested; the VPN feeds it what it sees and stores what it finds as a DNS
 * anomaly stamped with the network, so trust is judged per network.
 */
object NetworkSignals {

    /** Anomaly types (DnsAnomalyDetector.AnomalyType names) that are about the network. */
    const val DNS_TAMPERED = "DNS_TAMPERED"
    const val DNS_HIJACK = "DNS_HIJACK"
    const val TLS_INTERCEPTION = "TLS_INTERCEPTION"

    /** Package name stored on network findings: they belong to no app. */
    const val NETWORK_OWNER = "network"

    /**
     * Names that resolve to private addresses by design (dynamic-DNS helpers, Plex,
     * local development domains). A private answer for them is not tampering.
     */
    private val privateByDesign = listOf(
        "plex.direct", "nip.io", "sslip.io", "xip.io", "localtest.me", "lvh.me", "localhost",
        // Home-network names routers hand out (fritz.box, router.lan, nas.home.arpa).
        "box", "lan", "home", "home.arpa", "internal", "localdomain", "intranet", "corp", "router",
    )

    /**
     * A public name answered with a private, loopback or null address. On a network
     * that tampers with DNS, this is how redirects and rebinding look. Only meaningful
     * for plain DNS (DoH answers cannot be changed on the way).
     */
    fun looksTampered(name: String, answers: List<String>): Boolean {
        val n = name.lowercase().trimEnd('.')
        if (answers.isEmpty() || LocalNetwork.isLocalHost(n) || !n.contains('.')) return false
        if (privateByDesign.any { n == it || n.endsWith(".$it") }) return false
        return answers.any { ip ->
            ip == "0.0.0.0" || ip == "::" || LocalNetwork.isLocalAddress(ip)
        }
    }

    /**
     * Name for the hijack probe. ".invalid" is reserved (RFC 2606): no honest
     * resolver ever answers it with an address.
     */
    fun hijackProbeName(random: String): String = "${random.lowercase()}.privacyguard-check.invalid"

    /**
     * True when the answer to the probe has an address (the network rewrote or
     * answered DNS itself), false for an honest NXDOMAIN or empty answer, null when
     * there was no usable reply.
     */
    fun isHijacked(response: DnsPacket?): Boolean? {
        if (response == null || !response.isResponse) return null
        return response.aRecords.isNotEmpty() || response.aaaaRecords.isNotEmpty()
    }

    /**
     * A TLS handshake to a well-known server (our DoH provider) failed because its
     * certificate was not trusted: someone on the path presented a different one.
     * Timeouts and resets are not counted; they are not proof of interception.
     */
    fun isCertificateRejection(error: Throwable): Boolean {
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is java.security.cert.CertPathValidatorException ||
                t is java.security.cert.CertificateException ||
                t is javax.net.ssl.SSLPeerUnverifiedException) return true
            val msg = t.message.orEmpty()
            if (t is javax.net.ssl.SSLHandshakeException &&
                (msg.contains("Trust anchor", true) || msg.contains("certificate", true))) return true
            t = t.cause
        }
        return false
    }
}
