package com.privacyguard.app.core.geoip

data class GeoResult(
    val countryCode: String,
    val countryName: String,
    val org: String,
)

object GeoIpResolver {

    fun lookup(ip: String): GeoResult? {
        if (ip.contains(':')) return null  // IPv6 — fallback only
        val ipInt = ipToInt(ip) ?: return null
        return hardcodedLookup(ipInt, ip)
    }

    private fun hardcodedLookup(ipInt: Long, ip: String): GeoResult? {
        for ((network, bits, country, countryName, org) in KNOWN_RANGES) {
            val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
            if ((ipInt and mask) == (network and mask)) {
                return GeoResult(country, countryName, org)
            }
        }
        // Coarse continent-level fallback by first octet
        val firstOctet = ip.substringBefore('.').toIntOrNull() ?: return null
        return when (firstOctet) {
            in 1..126   -> GeoResult("US", "United States", "Unknown")
            in 128..191 -> GeoResult("US", "United States", "Unknown")
            in 192..223 -> GeoResult("EU", "Europe", "Unknown")
            else        -> null
        }
    }

    private fun ipToInt(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        return parts.fold(0L) { acc, s -> (acc shl 8) or (s.toLongOrNull() ?: return null) }
    }

    private data class Range(
        val network: Long,
        val bits: Int,
        val countryCode: String,
        val countryName: String,
        val org: String,
    )

    private fun range(cidr: String, country: String, countryName: String, org: String): Range {
        val (addr, prefix) = cidr.split('/')
        val parts = addr.split('.')
        val network = parts.fold(0L) { acc, s -> (acc shl 8) or s.toLong() }
        return Range(network, prefix.toInt(), country, countryName, org)
    }

    private val KNOWN_RANGES = listOf(
        // Google
        range("8.8.4.0/24",       "US", "United States", "Google LLC"),
        range("8.8.8.0/24",       "US", "United States", "Google LLC"),
        range("34.64.0.0/10",     "US", "United States", "Google Cloud"),
        range("35.184.0.0/13",    "US", "United States", "Google Cloud"),
        range("35.192.0.0/14",    "US", "United States", "Google Cloud"),
        range("64.233.160.0/19",  "US", "United States", "Google LLC"),
        range("66.102.0.0/20",    "US", "United States", "Google LLC"),
        range("66.249.64.0/19",   "US", "United States", "Google LLC"),
        range("72.14.192.0/18",   "US", "United States", "Google LLC"),
        range("74.125.0.0/16",    "US", "United States", "Google LLC"),
        range("142.250.0.0/15",   "US", "United States", "Google LLC"),
        range("172.217.0.0/16",   "US", "United States", "Google LLC"),
        range("173.194.0.0/16",   "US", "United States", "Google LLC"),
        range("209.85.128.0/17",  "US", "United States", "Google LLC"),
        range("216.58.192.0/19",  "US", "United States", "Google LLC"),
        range("216.239.32.0/19",  "US", "United States", "Google LLC"),

        // Cloudflare
        range("1.0.0.0/24",       "AU", "Australia",     "Cloudflare"),
        range("1.1.1.0/24",       "AU", "Australia",     "Cloudflare"),
        range("104.16.0.0/12",    "US", "United States", "Cloudflare"),
        range("104.24.0.0/14",    "US", "United States", "Cloudflare"),
        range("172.64.0.0/13",    "US", "United States", "Cloudflare"),
        range("198.41.128.0/17",  "US", "United States", "Cloudflare"),

        // Amazon AWS
        range("3.0.0.0/15",       "US", "United States", "Amazon Web Services"),
        range("13.32.0.0/15",     "US", "United States", "Amazon Web Services"),
        range("13.224.0.0/14",    "US", "United States", "Amazon CloudFront"),
        range("18.144.0.0/15",    "US", "United States", "Amazon Web Services"),
        range("52.0.0.0/8",       "US", "United States", "Amazon Web Services"),
        range("54.0.0.0/8",       "US", "United States", "Amazon Web Services"),
        range("99.77.0.0/16",     "US", "United States", "Amazon CloudFront"),
        range("143.204.0.0/16",   "US", "United States", "Amazon CloudFront"),
        range("205.251.192.0/19", "US", "United States", "Amazon Route 53"),

        // Microsoft / Azure
        range("13.64.0.0/11",     "US", "United States", "Microsoft Azure"),
        range("20.0.0.0/8",       "US", "United States", "Microsoft Azure"),
        range("40.64.0.0/10",     "US", "United States", "Microsoft Azure"),
        range("52.96.0.0/12",     "US", "United States", "Microsoft Azure"),
        range("104.40.0.0/13",    "US", "United States", "Microsoft Azure"),
        range("137.116.0.0/15",   "US", "United States", "Microsoft Azure"),

        // Meta / Facebook
        range("31.13.24.0/21",    "IE", "Ireland",       "Meta Platforms"),
        range("31.13.64.0/18",    "IE", "Ireland",       "Meta Platforms"),
        range("157.240.0.0/16",   "US", "United States", "Meta Platforms"),
        range("163.70.128.0/17",  "US", "United States", "Meta Platforms"),
        range("173.252.64.0/19",  "US", "United States", "Meta Platforms"),
        range("179.60.192.0/22",  "US", "United States", "Meta Platforms"),
        range("185.60.216.0/22",  "IE", "Ireland",       "Meta Platforms"),

        // Akamai
        range("2.16.0.0/13",      "EU", "Europe",        "Akamai Technologies"),
        range("23.32.0.0/11",     "US", "United States", "Akamai Technologies"),
        range("92.122.0.0/15",    "EU", "Europe",        "Akamai Technologies"),
        range("104.64.0.0/10",    "US", "United States", "Akamai Technologies"),
        range("184.24.0.0/13",    "US", "United States", "Akamai Technologies"),

        // Twitter / X Corp
        range("69.195.64.0/19",   "US", "United States", "X Corp"),
        range("104.244.40.0/21",  "US", "United States", "X Corp"),
        range("192.133.76.0/22",  "US", "United States", "X Corp"),

        // Fastly CDN
        range("23.235.32.0/20",   "US", "United States", "Fastly"),
        range("151.101.0.0/16",   "US", "United States", "Fastly"),
        range("199.232.0.0/16",   "US", "United States", "Fastly"),

        // Snapchat
        range("216.52.24.0/22",   "US", "United States", "Snap Inc"),

        // Tencent
        range("43.129.0.0/16",    "CN", "China",         "Tencent Cloud"),
        range("119.29.0.0/16",    "CN", "China",         "Tencent Cloud"),

        // Alibaba Cloud
        range("47.88.0.0/14",     "CN", "China",         "Alibaba Cloud"),
        range("106.11.0.0/16",    "CN", "China",         "Alibaba Cloud"),

        // Apple
        range("17.0.0.0/8",       "US", "United States", "Apple Inc"),

        // Yandex
        range("5.45.192.0/18",    "RU", "Russia",        "Yandex LLC"),
        range("77.88.0.0/18",     "RU", "Russia",        "Yandex LLC"),
        range("95.108.128.0/17",  "RU", "Russia",        "Yandex LLC"),
    )
}
