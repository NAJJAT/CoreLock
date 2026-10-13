package com.privacyguard.app.core.network

import com.privacyguard.app.data.db.ConnectionEntity

/**
 * Addresses that never leave your own network: home routers, printers, Chromecast,
 * smart-home devices, mDNS. Plain HTTP to them is how most of those devices work and
 * says nothing about privacy on the internet, so it is shown as "Local network" and
 * left out of every cleartext count, the privacy score and network trust.
 */
object LocalNetwork {

    fun isLocalAddress(ip: String?): Boolean {
        val a = ip?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (a.isEmpty()) return false
        return if (a.contains(':')) isLocalV6(a) else isLocalV4(a)
    }

    /** mDNS / Bonjour names ("printer.local") resolve on the local network only. */
    fun isLocalHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h.endsWith(".local") || h == "localhost"
    }

    fun isLocal(c: ConnectionEntity): Boolean =
        isLocalAddress(c.destinationIp) || isLocalHost(c.sniHostname) || isLocalHost(c.domain)

    private fun isLocalV4(a: String): Boolean {
        val p = a.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 ||                                   // 10.0.0.0/8
            (p[0] == 172 && p[1] in 16..31) ||                 // 172.16.0.0/12
            (p[0] == 192 && p[1] == 168) ||                    // 192.168.0.0/16
            (p[0] == 169 && p[1] == 254) ||                    // link-local
            p[0] == 127 ||                                     // loopback
            p[0] in 224..239 ||                                // multicast (mDNS 224.0.0.251, SSDP)
            (p[0] == 255 && p[1] == 255 && p[2] == 255 && p[3] == 255)  // broadcast
    }

    private fun isLocalV6(a: String): Boolean {
        if (a == "::1") return true
        val first = a.substringBefore(':').padStart(4, '0')
        val v = first.toIntOrNull(16) ?: return false
        return (v and 0xFFC0) == 0xFE80 ||   // fe80::/10 link-local
            (v and 0xFE00) == 0xFC00 ||      // fc00::/7 unique local
            (v and 0xFF00) == 0xFF00         // ff00::/8 multicast (mDNS ff02::fb)
    }
}
