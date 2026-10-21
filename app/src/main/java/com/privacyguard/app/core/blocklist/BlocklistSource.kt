package com.privacyguard.app.core.blocklist

private const val HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/"
// The same files through jsDelivr's GitHub mirror, tried when GitHub fails.
private const val HAGEZI_MIRROR = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/"

/**
 * Downloaded lists. Which protection level uses which is decided by category and,
 * for the ad lists, by source (ProtectionLevels).
 */
enum class BlocklistSource(private val path: String?, private val mirror: String? = null) {
    // OISD small, not big: big (238k names, mostly already in the other lists) took
    // the VPN's heap from 32 MB to 134 MB on a test phone. Strict only.
    OISD_BASIC("https://small.oisd.nl/domainswild2"),
    OISD_FULL(null),
    // HaGeZi Pro mini at Standard (~80k names), the full Pro at Strict (~207k).
    HAGEZI_PRO_MINI(HAGEZI + "wildcard/pro.mini-onlydomains.txt", HAGEZI_MIRROR + "wildcard/pro.mini-onlydomains.txt"),
    HAGEZI_PRO(HAGEZI + "wildcard/pro-onlydomains.txt", HAGEZI_MIRROR + "wildcard/pro-onlydomains.txt"),
    // Malware, phishing and scam hosts (HaGeZi Threat Intelligence Feeds, mini), every
    // level. Replaces URLhaus, whose host file answered 410 Gone from October 2026.
    HAGEZI_TIF_MINI(HAGEZI + "wildcard/tif.mini-onlydomains.txt", HAGEZI_MIRROR + "wildcard/tif.mini-onlydomains.txt"),
    // Encrypted DNS endpoints (DnsBypassGuard), not blocked by DNS: a listed name or
    // address is stopped at the connection, so apps fall back to DNS the VPN sees.
    HAGEZI_DOH(HAGEZI + "wildcard/doh-onlydomains.txt", HAGEZI_MIRROR + "wildcard/doh-onlydomains.txt"),
    HAGEZI_DOH_IPS(HAGEZI + "ips/doh.txt", HAGEZI_MIRROR + "ips/doh.txt"),
    CUSTOM(null),
    ;

    fun downloadUrl(): String? = path

    /** A second host for the same file, or null. */
    fun mirrorUrl(): String? = mirror
}
