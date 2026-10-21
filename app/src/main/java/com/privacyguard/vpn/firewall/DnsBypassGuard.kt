package com.privacyguard.vpn.firewall

/**
 * Keeps apps on the tunnel's DNS so the blocklist cannot be skipped.
 *
 * Plain DNS (UDP/53 to any address) is answered by DnsHandler, which only ever asks
 * the user's chosen resolver. What is left are encrypted resolvers an app can reach
 * directly: DNS over TLS / QUIC on port 853 (also used by Android Private DNS) and DNS
 * over HTTPS on 443. Those are stopped so the app falls back to plain DNS through the
 * tunnel.
 *
 * DoH endpoints come from two sources:
 *  - Built in ([BUILT_IN_HOSTS], [BUILT_IN_IPS]): always on, so protection holds on
 *    first start and when a download fails. The built-in addresses are dropped at the
 *    SYN ([isDohAddress]).
 *  - Downloaded (HaGeZi's DoH lists, through the blocklist pipeline): [update] swaps
 *    them in whole. A downloaded address is judged at the ClientHello
 *    ([blocksClientHello]): many share an address with ordinary sites, so a normal
 *    server name passes and only DoH names, or no name at all, are stopped.
 *
 * A user Allow rule lets a DoH endpoint through (port 853 stays closed).
 * PrivacyGuard's own DoH upstream uses protected sockets that bypass the tunnel, so it
 * is not affected.
 */
object DnsBypassGuard {

    const val DOT_PORT = 853
    private const val HTTPS_PORT = 443

    /** DoH endpoints by SNI. A listed name also matches its subdomains. */
    private val BUILT_IN_HOSTS = setOf(
        "dns.google", "dns.google.com", "dns64.dns.google",
        "cloudflare-dns.com", "one.one.one.one",
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
        "doh.opendns.com", "doh.familyshield.opendns.com",
        "dns.adguard.com", "dns.adguard-dns.com", "dns-unfiltered.adguard.com",
        "doh.cleanbrowsing.org", "dns.nextdns.io",
        "doh.mullvad.net", "dns.mullvad.net",
        "freedns.controld.com", "doh.dns.sb", "dns.alidns.com", "doh.pub",
    )

    /**
     * Public resolvers that also serve DoH on 443 by bare IP. IPv6 entries are in the
     * RFC 5952 form Ipv6Packet.parseAddress produces.
     */
    private val BUILT_IN_IPS = setOf(
        "8.8.8.8", "8.8.4.4",
        "1.1.1.1", "1.0.0.1",
        "9.9.9.9", "149.112.112.112",
        "208.67.222.222", "208.67.220.220",
        "94.140.14.14", "94.140.15.15",
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2620:fe::fe", "2620:fe::9",
        "2620:119:35::35", "2620:119:53::53",
        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
    )

    /** Built-in plus downloaded names; DomainFilter swaps its sets atomically. */
    private val hosts = DomainFilter().apply { rebuild(BUILT_IN_HOSTS) }

    /** Downloaded addresses, already validated and normalized (ListEntryValidator.normalizeIp). */
    @Volatile private var downloadedIps: Set<String> = emptySet()

    /**
     * Replaces the downloaded lists. Empty lists (nothing downloaded yet, a failed
     * download, the lists switched off) leave the built-in sets.
     */
    fun update(downloadedHosts: Collection<String>, downloadedAddresses: Collection<String>) {
        hosts.rebuild(if (downloadedHosts.isEmpty()) BUILT_IN_HOSTS else BUILT_IN_HOSTS + downloadedHosts)
        downloadedIps = downloadedAddresses.toHashSet()
    }

    /** DNS over TLS / DNS over QUIC, on any protocol. */
    fun isEncryptedDnsPort(port: Int): Boolean = port == DOT_PORT

    /**
     * Plain DNS that DnsHandler never sees: DNS over TCP (it only answers UDP), and
     * any DNS over IPv6 (the tunnel only offers an IPv4 resolver). Dropping it makes
     * the app fall back to UDP through the tunnel's resolver.
     */
    fun isUnfilteredPlainDns(port: Int): Boolean = port == 53

    /** True for a DoH endpoint's name (built in or downloaded), or a subdomain of one. */
    fun isDohHost(sni: String?): Boolean {
        if (sni.isNullOrBlank()) return false
        return hosts.isBlocked(sni)
    }

    /** A built-in DoH resolver on 443: dropped at the SYN, whatever server name follows. */
    fun isDohAddress(ip: String, port: Int): Boolean = port == HTTPS_PORT && ip in BUILT_IN_IPS

    /** A downloaded DoH address on 443: judged at the ClientHello, see [blocksClientHello]. */
    fun isListedDohAddress(ip: String, port: Int): Boolean = port == HTTPS_PORT && ip in downloadedIps

    /**
     * Encrypted DNS stopped before any data, for TCP and UDP alike: port 853 always
     * (DOT), and a built-in DoH resolver address on 443 (DOH) unless the user allowed
     * that address. Downloaded addresses are left to [blocksClientHello].
     */
    fun blockedBeforeData(
        dstIp: String, dstPort: Int, userAllowsAddress: (String) -> Boolean,
    ): com.privacyguard.core.session.BlockSource? = when {
        isEncryptedDnsPort(dstPort) -> com.privacyguard.core.session.BlockSource.DOT
        isDohAddress(dstIp, dstPort) && !userAllowsAddress(dstIp) -> com.privacyguard.core.session.BlockSource.DOH
        else -> null
    }

    /**
     * Whether a TLS or QUIC connection is a DNS bypass, from its first message: its
     * server name is a DoH endpoint, or it sends none to a listed DoH address. A normal
     * server name passes, also to a listed address. A user Allow rule for the name (or,
     * without a name, for the address) lets it through.
     */
    fun blocksClientHello(
        sni: String?, dstIp: String, dstPort: Int,
        userAllowsName: (String) -> Boolean,
        userAllowsAddress: (String) -> Boolean,
    ): Boolean =
        if (!sni.isNullOrBlank()) isDohHost(sni) && !userAllowsName(sni)
        else isListedDohAddress(dstIp, dstPort) && !userAllowsAddress(dstIp)
}
