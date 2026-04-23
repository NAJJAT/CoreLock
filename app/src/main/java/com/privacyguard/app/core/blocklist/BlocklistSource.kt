package com.privacyguard.app.core.blocklist

enum class BlocklistSource(private val url: String?) {
    STEVENBLACK("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
    EASYLIST("https://easylist.to/easylist/easylist.txt"),
    EASYPRIVACY("https://easylist.to/easylist/easyprivacy.txt"),
    OISD_BASIC("https://basic.oisd.nl/domains"),
    OISD_FULL("https://full.oisd.nl/domains"),
    HAGEZI_LIGHT("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/light.txt"),
    CUSTOM(null),
    ;

    fun downloadUrl(): String? = url
}
