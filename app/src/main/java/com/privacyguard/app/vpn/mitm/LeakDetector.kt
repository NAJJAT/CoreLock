package com.privacyguard.vpn.mitm

/**
 * Finds personal data in an outgoing request (URL, headers and decoded body):
 * location, advertising IDs, device identifiers, network info, email, phone.
 *
 * Matching is key-based (`"lat": 52.1`, `lat=52.1`, `X-Device-Id: …`) so that
 * random numbers in unrelated fields are not reported. Pure JVM for testing.
 */
object LeakDetector {

    enum class LeakType(val label: String, val description: String) {
        LOCATION("Location", "GPS coordinates are being sent — this is how shops and ad networks know where you were."),
        ADVERTISING_ID("Advertising ID", "Your advertising ID links this request to your ad profile across apps."),
        DEVICE_ID("Device ID", "A hardware or install identifier lets the receiver recognise this phone."),
        NETWORK_INFO("Network info", "Wi-Fi names, MAC/BSSID or carrier codes can reveal where you are."),
        EMAIL("Email", "Your email address is included in this request."),
        PHONE_NUMBER("Phone number", "A phone number is included in this request."),
    }

    /** Name of the pseudo-header used to store detected leaks with a captured payload. */
    const val LEAKS_HEADER = ":leaks"

    private const val SEP = """["']?\s*[:=]\s*["']?"""
    private const val UUID = """[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"""

    private fun keyed(keys: String, value: String) =
        Regex("""(?i)(?:^|[^A-Za-z0-9])(?:$keys)$SEP($value)""")

    private val rules: List<Pair<LeakType, Regex>> = listOf(
        LeakType.LOCATION to keyed(
            "lat|latitude|lng|lon|long|longitude|geo_?lat|geo_?lon|user_?lat|user_?lng",
            """-?\d{1,3}\.\d{3,}"""
        ),
        // "ll=52.3702,4.8952", "location":"52.3702,4.8952", geo:52.37,4.89
        LeakType.LOCATION to keyed(
            "ll|latlng|lat_?lng|lat_?lon|location|loc|geo|coordinates|position",
            """-?\d{1,2}\.\d{3,}\s*(?:,|%2C)\s*-?\d{1,3}\.\d{3,}"""
        ),
        LeakType.ADVERTISING_ID to keyed(
            "gaid|adid|ad_?id|aaid|idfa|advertising_?id|advertiser_?id|google_?ad_?id|ifa|oaid|x-ad-id",
            UUID
        ),
        LeakType.DEVICE_ID to keyed(
            "android_?id|device_?id|deviceid|imei|meid|serial(?:_?number)?|udid|hardware_?id|x-device-id",
            """[0-9A-Za-z-]{8,}"""
        ),
        LeakType.DEVICE_ID to Regex("""(?i)(?:^|[^A-Za-z0-9])imei$SEP(\d{15})"""),
        LeakType.NETWORK_INFO to keyed(
            "bssid|mac|mac_?address|wifi_?mac|router_?mac",
            """(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}"""
        ),
        LeakType.NETWORK_INFO to keyed("ssid|wifi_?name|wifi_?ssid", """[^"'&,\s]{2,}"""),
        LeakType.NETWORK_INFO to keyed("mcc|mnc", """\d{2,3}"""),
        LeakType.NETWORK_INFO to keyed("carrier|carrier_?name|network_?operator(?:_?name)?", """[A-Za-z][A-Za-z0-9 &.-]+"""),
        // Excludes asset names like "icon@2x.png".
        LeakType.EMAIL to Regex(
            """\b([A-Za-z0-9._%+-]+(?:@|%40)[A-Za-z0-9.-]+\.(?!(?:png|jpe?g|gif|webp|svg|js|css)\b)[A-Za-z]{2,})\b"""
        ),
        LeakType.PHONE_NUMBER to keyed("phone|phone_?number|msisdn|mobile|tel", """\+?[0-9][0-9 ()-]{6,}[0-9]"""),
    )

    /** Leak types found in [text]. */
    fun detect(text: String?): Set<LeakType> {
        if (text.isNullOrEmpty()) return emptySet()
        return rules.filter { (_, regex) -> regex.containsMatchIn(text) }
            .mapTo(LinkedHashSet()) { it.first }
    }

    fun detect(urlPath: String?, headers: Map<String, String>, body: String?): Set<LeakType> {
        val headerText = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        return detect(urlPath) + detect(headerText) + detect(body)
    }

    fun encode(leaks: Set<LeakType>): String = leaks.joinToString(",") { it.name }

    fun decode(value: String?): Set<LeakType> =
        value?.split(',')?.mapNotNullTo(LinkedHashSet()) { name ->
            LeakType.entries.firstOrNull { it.name == name.trim() }
        } ?: emptySet()
}
