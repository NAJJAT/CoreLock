package com.privacyguard.app.ui.settings

import com.privacyguard.app.core.blocklist.BlocklistSource
import com.privacyguard.app.data.local.preferences.SettingsPreferences

/**
 * Pure decisions of Settings › Advanced (DESIGN_SPEC_1.1.md §6.6): DNS server choices,
 * proxy port input and the names of the downloaded lists. No Android types.
 */
object AdvancedLogic {

    /** A DNS server the user can pick with one tap, and the DoH provider that goes with it. */
    enum class DnsPreset(val label: String, val ip: String, val dohProvider: String) {
        CLOUDFLARE("Cloudflare", "1.1.1.1", SettingsPreferences.DOH_CLOUDFLARE),
        GOOGLE("Google", "8.8.8.8", SettingsPreferences.DOH_GOOGLE),
        QUAD9("Quad9", "9.9.9.9", SettingsPreferences.DOH_QUAD9),
    }

    /** The preset of [upstreamDns], or null when the user typed another server ("Other server"). */
    fun presetOf(upstreamDns: String): DnsPreset? = DnsPreset.entries.firstOrNull { it.ip == upstreamDns.trim() }

    /** Name of the encrypted DNS provider, as 1.0 showed it (Cloudflare when unknown, the app default). */
    fun providerLabel(provider: String): String = when (provider) {
        SettingsPreferences.DOH_GOOGLE -> DnsPreset.GOOGLE.label
        SettingsPreferences.DOH_QUAD9 -> DnsPreset.QUAD9.label
        else -> DnsPreset.CLOUDFLARE.label
    }

    /**
     * The proxy port in [input], or null when it is not one (1 to 65535). The screen keeps
     * what was typed and saves only a valid port: 1.0 saved 1080 the moment the field was
     * empty, so clearing "1080" to type "9050" jumped back to "1080", and it stored ports
     * above 65535 that no connection can use.
     */
    fun parsePort(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** A proxy host is a name or an address without spaces or a scheme; the engine resolves it. */
    fun isValidProxyHost(input: String): Boolean {
        val s = input.trim()
        return s.isNotEmpty() && s.length <= 253 && s.none { it.isWhitespace() || it == '/' || it == '@' } && "://" !in s
    }

    /**
     * The proxy chain can only work with both servers filled in. The switch says so instead
     * of seeming on while connections fail.
     */
    fun proxyChainComplete(host1: String, port1: Int, host2: String, port2: Int): Boolean =
        isValidProxyHost(host1) && isValidProxyHost(host2) && port1 in 1..65535 && port2 in 1..65535

    /** A built-in list's category, shown with the translated category name. */
    enum class BuiltInCategory { ADS, TRACKERS, TELEMETRY, MALWARE }

    /** How a list stored in the database is named on screen. */
    sealed interface StoredList {
        data class Named(val name: String) : StoredList
        data class BuiltIn(val category: BuiltInCategory) : StoredList
        /** A key this version doesn't know (an older list): shown as stored rather than hidden. */
        data class Unknown(val key: String) : StoredList
    }

    /**
     * The database stores a key per list ("Hagezi-TIF-mini", "BuiltIn-ads"; see
     * BlocklistRepo.SOURCE_*). Diagnostics showed those keys raw; this gives the published
     * name, or the category of a built-in list.
     */
    fun storedList(key: String): StoredList {
        STORED_NAMES[key.lowercase()]?.let { return StoredList.Named(it) }
        if (key.startsWith("BuiltIn-", ignoreCase = true)) {
            val category = when (key.substringAfter('-').lowercase()) {
                "ads" -> BuiltInCategory.ADS
                "trackers" -> BuiltInCategory.TRACKERS
                "telemetry" -> BuiltInCategory.TELEMETRY
                "malware" -> BuiltInCategory.MALWARE
                else -> null
            }
            if (category != null) return StoredList.BuiltIn(category)
        }
        return StoredList.Unknown(key)
    }

    private val STORED_NAMES = mapOf(
        "oisd" to "OISD small",
        "hagezi-pro-mini" to "HaGeZi Pro mini",
        "hagezi-pro" to "HaGeZi Pro",
        "hagezi-tif-mini" to "HaGeZi TIF mini",
        "hagezi-doh" to "HaGeZi DoH",
        "hagezi-doh-ips" to "HaGeZi DoH IPs",
    )

    /**
     * Names of the lists CoreLock downloads, as their authors publish them (proper names,
     * not translated; the same names as the README and BLOCKING_POLICY.md). Lists that are
     * never downloaded keep their code name: Diagnostics shows only downloaded ones.
     */
    fun listName(source: BlocklistSource): String = when (source) {
        BlocklistSource.OISD_BASIC -> "OISD small"
        BlocklistSource.HAGEZI_PRO_MINI -> "HaGeZi Pro mini"
        BlocklistSource.HAGEZI_PRO -> "HaGeZi Pro"
        BlocklistSource.HAGEZI_TIF_MINI -> "HaGeZi TIF mini"
        BlocklistSource.HAGEZI_DOH -> "HaGeZi DoH"
        BlocklistSource.HAGEZI_DOH_IPS -> "HaGeZi DoH IPs"
        BlocklistSource.OISD_FULL, BlocklistSource.CUSTOM -> source.name
    }
}
