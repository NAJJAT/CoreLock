package com.privacyguard.app.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the string resources tell the user, in every language:
 *
 * - The app is called CoreLock (owner, 2026-11-03). "PrivacyGuard" stays only where it
 *   names something that really is still called that: the HTTPS-inspection certificate
 *   of builds up to 1.1 ("PrivacyGuard Enterprise CA"; since the 90-day CA rotation it is
 *   "CoreLock HTTPS Inspection CA", ADR 0009, but users must still find and remove old
 *   ones), the files those builds saved, and the SIEM signature header (receivers may
 *   check it).
 * - Strict does not block Google Safe Browsing (removed in 1.0.0, BuiltInSeedTest):
 *   blocking it turned off Chrome's phishing and malware warnings, and no text may say
 *   otherwise.
 */
class UserVisibleTextTest {

    private val resDirs = listOf("src/main/res", "src/enterprise/res").map { File(it) }.filter { it.isDirectory }

    private data class Entry(val locale: String, val file: String, val key: String, val text: String)

    /**
     * Strings allowed to say "PrivacyGuard", and why. Everywhere else the only allowed
     * form is the certificate's exact name, "PrivacyGuard Enterprise CA" (the text tells
     * users which certificate to look for in Android's settings).
     */
    private val allowedToSayPrivacyGuard = mapOf(
        "mitm_install_steps" to "names the PrivacyGuard-CA files that builds up to 1.1 saved to Downloads",
        "restriction_mitm_siem_signing_key_desc" to "names the X-PrivacyGuard-Sig header",
    )
    private val certificateName = "PrivacyGuard Enterprise CA"

    /** Any spelling: PrivacyGuard, privacyguard, Privacy Guard, privacy_guard, PRIVACY-GUARD. */
    private val oldName = Regex("privacy[_ -]?guard", RegexOption.IGNORE_CASE)

    @Test
    fun no_user_visible_string_says_privacyguard_outside_the_certificate_texts() {
        val hits = entries()
            .filter { it.key !in allowedToSayPrivacyGuard }
            .filter { oldName.containsMatchIn(it.text.replace(certificateName, "")) }
            .map { "${it.locale}/${it.file}: ${it.key}" }
        if (hits.isNotEmpty()) fail("Still says PrivacyGuard (the app is CoreLock):\n" + hits.joinToString("\n"))
    }

    /**
     * String literals in the app's Kotlin code that may contain the old name, and why.
     * Each one is stored on users' phones or relied on by others, so renaming it would lose
     * data, break updates or break organisations' setups (docs/architecture/CODE_MAP.md,
     * "Internal names"). Anything else with the old name in a literal can reach a user
     * (a file name, a notification, a report), so it must say CoreLock.
     */
    private val oldNameLiteralsThatStay = mapOf(
        "privacyguard.db" to "history database file",
        "privacyguard_settings" to "SharedPreferences file: the user's settings",
        "privacyguard_secure_store" to "SharedPreferences file: Keystore-wrapped secrets",
        "privacyguard_boot" to "SharedPreferences file: restart after reboot",
        "privacyguard_pause" to "SharedPreferences file: pause end time",
        "privacyguard_watchdog" to "SharedPreferences file: crash watchdog state",
        "privacyguard_unexpected_stop" to "SharedPreferences file: the last unexpected stop",
        "privacyguard_enterprise_ca" to "Keystore alias of the inspection CA key",
        "privacyguard_high" to "notification channel ID (users' channel settings belong to it)",
        "privacyguard_normal" to "notification channel ID",
        "privacyguard_low" to "notification channel ID",
        "privacyguard_report" to "notification channel ID",
        "privacyguard-blocklist-cache 1" to "header of the start-up cache files written by earlier versions",
        "X-PrivacyGuard-Sig" to "SIEM signature header; receivers may check it",
        "PrivacyGuard/1.0" to "User-Agent of list downloads (BrandNameTest)",
        "PrivacyGuard Enterprise CA" to "name of the CA of builds up to 1.1, still recognised to remove it",
        "PrivacyGuard" to "organisation of that old CA, and the Download/PrivacyGuard/ folder of CA exports on Android 9 and older (FileProvider path)",
        "PrivacyGuard-CA-" to "file names of CA exports of builds up to 1.1, deleted with new exports",
        "\${random.lowercase()}.privacyguard-check.invalid" to "DNS-hijack probe name; never shown, and .invalid never resolves",
    )

    private val kotlinRoots = listOf("src/main/java", "src/enterprise/java", "src/debug/java").map { File(it) }

    /** "…" literals on code lines (comments skipped), with qualified names (com.privacyguard.…) removed. */
    private fun oldNameLiterals(): List<Pair<String, String>> = kotlinRoots.filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .flatMap { file ->
            file.readLines()
                .filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("//") || l.startsWith("/*") } }
                .flatMap { line -> Regex("\"(?:[^\"\\\\]|\\\\.)*\"").findAll(line).map { it.value.removeSurrounding("\"") }.toList() }
                .filter { oldName.containsMatchIn(it.replace(Regex("""com\.privacyguard[\w.:]*"""), "")) }
                .map { file.path to it }
        }

    @Test
    fun no_kotlin_literal_says_privacyguard_outside_the_internal_names() {
        val literals = oldNameLiterals()
        assertTrue("read no Kotlin code", literals.isNotEmpty())
        val hits = literals.filter { (_, text) -> text !in oldNameLiteralsThatStay }.map { (file, text) -> "$file: \"$text\"" }
        if (hits.isNotEmpty()) fail("A literal still says PrivacyGuard (the app is CoreLock):\n" + hits.joinToString("\n"))
        // An entry no code uses any more must leave the list, or it would hide a regression.
        assertEquals(emptySet<String>(), oldNameLiteralsThatStay.keys - literals.map { it.second }.toSet())
    }

    @Test
    fun the_app_label_and_the_tile_say_corelock() {
        val english = entries().filter { it.locale == "values" }.associate { it.key to it.text }
        assertEquals("CoreLock", english["app_name"])
        assertEquals("CoreLock VPN", english["tile_label"])
    }

    @Test
    fun the_allow_list_is_only_as_long_as_it_needs_to_be() {
        // An entry that no longer says PrivacyGuard must leave the list, or it would hide a regression.
        val stillNeeded = entries().filter { "PrivacyGuard" in it.text }.map { it.key }.toSet()
        assertEquals(emptySet<String>(), allowedToSayPrivacyGuard.keys - stillNeeded)
    }

    /** "Safe Browsing" in the four languages, and the name of the Strict level in each. */
    private val safeBrowsing = Regex("""safe ?browsing|التصفح الآمن|navigation sécurisée|veilig browsen|veilig surfen""", RegexOption.IGNORE_CASE)
    private val strictNames = mapOf("values" to "Strict", "values-fr" to "Strict", "values-nl" to "Streng", "values-ar" to "صارم")

    @Test
    fun no_string_says_strict_blocks_safe_browsing() {
        val hits = entries().filter { e ->
            safeBrowsing.containsMatchIn(e.text) &&
                (e.key.startsWith("set_level_strict") || strictNames[e.locale]?.let { it in e.text } == true)
        }.map { "${it.locale}/${it.file}: ${it.key} = ${it.text}" }
        if (hits.isNotEmpty()) fail("Says Strict blocks Safe Browsing (it does not, since 1.0.0):\n" + hits.joinToString("\n"))
    }

    @Test
    fun the_checks_would_catch_the_old_texts() {
        // The 1.0 release candidate text, and the old app label.
        val old = "Strict also blocks system telemetry (Microsoft, Apple, Amazon, Mozilla, Google Safe Browsing)."
        assertTrue(safeBrowsing.containsMatchIn(old) && "Strict" in old)
        assertTrue(safeBrowsing.containsMatchIn("(Microsoft وApple وAmazon وMozilla وGoogle Safe Browsing)"))
        assertTrue("PrivacyGuard" in "PrivacyGuard stopped unexpectedly.".replace(certificateName, ""))
        assertTrue("PrivacyGuard" !in "remove every “PrivacyGuard Enterprise CA” except the newest".replace(certificateName, ""))
        // The IT report was saved as privacyguard-report-….pdf until 1.2.1: lower case.
        assertTrue(oldName.containsMatchIn("privacyguard-report-1.pdf") && "privacyguard-report-" !in oldNameLiteralsThatStay)
    }

    @Test
    fun all_four_languages_were_read() {
        val locales = entries().map { it.locale }.toSet()
        assertTrue("found $locales", locales.containsAll(listOf("values", "values-ar", "values-fr", "values-nl")))
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun entries(): List<Entry> = resDirs.flatMap { res ->
        res.listFiles { f -> f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) }.orEmpty()
            .flatMap { dir ->
                dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
                    .flatMap { parse(dir.name, it) }
            }
    }

    private fun parse(locale: String, file: File): List<Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableListOf<Entry>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val el = strings.item(i) as Element
            out += Entry(locale, file.name, el.getAttribute("name"), el.textContent)
        }
        for (tag in listOf("plurals", "string-array")) {
            val groups = doc.getElementsByTagName(tag)
            for (i in 0 until groups.length) {
                val group = groups.item(i) as Element
                val items = group.getElementsByTagName("item")
                for (j in 0 until items.length) {
                    out += Entry(locale, file.name, group.getAttribute("name"), (items.item(j) as Element).textContent)
                }
            }
        }
        return out
    }
}
