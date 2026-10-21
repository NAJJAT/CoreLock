package com.privacyguard.app.core.blocklist

enum class BlocklistSource(private val url: String?) {
    STEVENBLACK("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
    EASYLIST("https://easylist.to/easylist/easylist.txt"),
    EASYPRIVACY("https://easylist.to/easylist/easyprivacy.txt"),
    // OISD and HaGeZi moved their files; the old URLs answered 404, so these lists
    // never loaded. OISD small, not big: big (238k names, mostly already in the other
    // lists) took the VPN's heap from 32 MB to 134 MB on a test phone.
    OISD_BASIC("https://small.oisd.nl/domainswild2"),
    OISD_FULL(null),
    HAGEZI_LIGHT("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt"),
    // Malware distribution hosts (abuse.ch). Before this, the malware category held
    // only a handful of built-in names.
    URLHAUS("https://urlhaus.abuse.ch/downloads/hostfile/"),
    // Encrypted DNS endpoints (DnsBypassGuard), not blocked by DNS: a listed name or
    // address is stopped at the connection, so apps fall back to DNS the VPN sees.
    HAGEZI_DOH("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/doh-onlydomains.txt"),
    HAGEZI_DOH_IPS("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/ips/doh.txt"),
    CUSTOM(null),
    ;

    fun downloadUrl(): String? = url
}
