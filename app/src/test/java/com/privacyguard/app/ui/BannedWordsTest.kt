package com.privacyguard.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Everyday screens speak plainly (DESIGN_SPEC_1.1.md §7.1): the English strings that
 * Home, Apps, Activity, Settings (not Advanced) and onboarding use must not contain the
 * technical words listed there. Technical details sections may use them; those strings
 * are listed in [ALLOWED] with the reason, so a new technical word can't slip into an
 * everyday sentence unnoticed.
 */
class BannedWordsTest {

    private val ui = "src/main/java/com/privacyguard/app/ui"

    /** The everyday screens: every file of Home, Activity and onboarding, and the main files of Apps and Settings. */
    private fun screenFiles(): List<File> =
        listOf("home", "activity", "onboarding").flatMap { dir ->
            File("$ui/$dir").listFiles().orEmpty().filter { it.extension == "kt" }
        } + listOf("apps/AppsScreen.kt", "apps/AppDetailScreen.kt", "settings/SettingsHomeScreen.kt").map { File("$ui/$it") }

    /** Acronyms match by case; ordinary words in any case, also as a plural. */
    private val banned: List<Regex> =
        listOf("DNS", "SNI", "TLS", "QUIC", "DoH", "DoT", "TCP", "UDP", "SOCKS5", "PCAP", "SIEM", "JA3", "JA4", "CA", "SDK")
            .map { Regex("""\b$it\b""") } +
            listOf("payload", "tunnel", "cleartext", "resolver", "packet", "port")
                .map { Regex("""\b${it}s?\b""", RegexOption.IGNORE_CASE) }

    private val ALLOWED = mapOf(
        // Technical details sections (DESIGN_SPEC §7.1 allows them there).
        "ui_tech_port" to "Activity › Technical details",
        "conn_blocked_by_dns" to "Activity › Technical details › Stopped by",
        "conn_blocked_by_sni" to "Activity › Technical details › Stopped by",
        "conn_blocked_by_quic" to "Activity › Technical details › Stopped by",
        "conn_blocked_by_doh" to "Activity › Technical details › Stopped by",
        "conn_blocked_by_dot" to "Activity › Technical details › Stopped by",
        "appd_dns" to "App detail › Technical details",
        "appd_top_dns" to "App detail › Technical details",
        "appd_no_dns" to "App detail › Technical details",
        "appd_loading_payloads" to "App detail › Technical details › HTTPS inspection",
        "appd_no_payloads" to "App detail › Technical details › HTTPS inspection",
        // Onboarding's "What leaves your phone" sheet: the full technical disclosure, on purpose.
        "onb_c_net_body" to "onboarding › What leaves your phone",
        "onb_c_storage_body" to "onboarding › What leaves your phone",
        // Android's own name for the setting; the user has to find it under that name.
        "ui_attention_private_dns" to "\"Private DNS\" is the Android setting's name",
    )

    private fun englishStrings(): Map<String, String> {
        val out = HashMap<String, String>()
        val entry = Regex("""<(string|plurals) name="(\w+)"[^>]*>([\s\S]*?)</\1>""")
        File("src/main/res/values").listFiles().orEmpty().filter { it.name.startsWith("strings") }.forEach { f ->
            entry.findAll(f.readText()).forEach { out[it.groupValues[2]] = it.groupValues[3] }
        }
        return out
    }

    @Test
    fun everyday_screens_use_no_technical_words() {
        val files = screenFiles()
        assertTrue("screen files not found; wrong working directory?", files.size > 5 && files.all { it.isFile })
        val used = files.flatMap { f -> Regex("""R\.(?:string|plurals)\.(\w+)""").findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        val text = englishStrings()
        assertTrue("strings not found", used.count { it in text } > 100)
        val hits = used.filter { it !in ALLOWED }.mapNotNull { name ->
            val t = text[name] ?: return@mapNotNull null
            banned.firstOrNull { it.containsMatchIn(t) }?.let { "$name: \"${it.pattern}\" in \"$t\"" }
        }
        assertTrue("Technical words on everyday screens (§7.1):\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun allowed_strings_are_still_used() {
        val used = screenFiles().joinToString("\n") { it.readText() }
        val stale = ALLOWED.keys.filter { "R.string.$it" !in used && "R.plurals.$it" !in used }
        assertTrue("No longer used on these screens; remove from ALLOWED: $stale", stale.isEmpty())
    }
}
