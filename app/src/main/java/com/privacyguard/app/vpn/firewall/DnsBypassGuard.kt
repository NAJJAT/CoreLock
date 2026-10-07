package com.privacyguard.vpn.firewall

/**
 * Keeps apps on the tunnel's DNS so the blocklist cannot be skipped.
 *
 * Plain DNS (UDP/53 to any address) is already answered by DnsHandler. What is
 * left are encrypted resolvers an app can reach directly: DNS over TLS on port
 * 853 (also used by Android Private DNS) and DNS over HTTPS on 443. Those are
 * dropped so the app falls back to plain DNS through the tunnel.
 *
 * PrivacyGuard's own DoH upstream uses protected sockets that bypass the
 * tunnel, so it is not affected.
 */
object DnsBypassGuard {

    const val DOT_PORT = 853

    /** DoH endpoints by SNI. A listed name also matches its subdomains. */
    private val DOH_HOSTS = setOf(
        "dns.google", "dns.google.com", "dns64.dns.google",
        "cloudflare-dns.com", "one.one.one.one",
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
        "doh.opendns.com", "doh.familyshield.opendns.com",
        "dns.adguard.com", "dns.adguard-dns.com", "dns-unfiltered.adguard.com",
        "doh.cleanbrowsing.org", "dns.nextdns.io",
        "doh.mullvad.net", "dns.mullvad.net",
        "freedns.controld.com", "doh.dns.sb", "dns.alidns.com", "doh.pub",
    )

    /** Public resolvers that also serve DoH on 443 by bare IP. */
    private val DOH_IPS = setOf(
        "8.8.8.8", "8.8.4.4",
        "1.1.1.1", "1.0.0.1",
        "9.9.9.9", "149.112.112.112",
        "208.67.222.222", "208.67.220.220",
        "94.140.14.14", "94.140.15.15",
    )

    /** DNS over TLS / DNS over QUIC, on any protocol. */
    fun isEncryptedDnsPort(port: Int): Boolean = port == DOT_PORT

    fun isDohHost(sni: String?): Boolean {
        if (sni.isNullOrBlank()) return false
        val host = sni.lowercase().trimEnd('.')
        return DOH_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun isDohAddress(ip: String, port: Int): Boolean = port == 443 && ip in DOH_IPS
}
