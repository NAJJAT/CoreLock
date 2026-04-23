package com.privacyguard.core.filter

/** Category of blocked content. */
enum class BlocklistCategory(val displayName: String, val emoji: String) {
    ADS("Advertisements", "📢"),
    TRACKERS("Trackers", "🕵️"),
    MALWARE("Malware & Phishing", "☠️"),
    TELEMETRY("Telemetry", "📡"),
    SOCIAL("Social Widgets", "👥"),
    CRYPTO("Cryptomining", "⛏️"),
    ADULT("Adult Content", "🔞"),
    CUSTOM("Custom Rules", "⚙️"),
}

/** Known blocklist source identifiers. */
object BlocklistSource {
    const val STEVEN_BLACK = "StevenBlack"
    const val OISD_BASIC   = "OISD-Basic"
    const val OISD_FULL    = "OISD-Full"
    const val EASYLIST     = "EasyList"
    const val HAGEZI_LIGHT = "HaGeZi-Light"
    const val HAGEZI_PRO   = "HaGeZi-Pro"
    const val USER_CUSTOM  = "User"

    /** Download URLs keyed by source identifier. */
    val urls: Map<String, String> = mapOf(
        STEVEN_BLACK to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        OISD_BASIC   to "https://basic.oisd.nl/domains",
        OISD_FULL    to "https://full.oisd.nl/domains",
        HAGEZI_LIGHT to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/light.txt",
        HAGEZI_PRO   to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt",
    )
}