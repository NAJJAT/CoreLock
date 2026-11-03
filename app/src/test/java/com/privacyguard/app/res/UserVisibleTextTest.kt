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
 *   ("PrivacyGuard Enterprise CA", renamed with the 90-day CA rotation, ADR 0009) and the
 *   SIEM signature header (receivers may check it).
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
        "dash_card_decrypt_on_desc" to "names the inspection certificate (organisation PrivacyGuard)",
        "restriction_ca_install_automatic_desc" to "MDM text about the PrivacyGuard CA and what it signs",
        "restriction_mitm_siem_signing_key_desc" to "names the X-PrivacyGuard-Sig header",
    )
    private val certificateName = "PrivacyGuard Enterprise CA"

    @Test
    fun no_user_visible_string_says_privacyguard_outside_the_certificate_texts() {
        val hits = entries()
            .filter { it.key !in allowedToSayPrivacyGuard }
            .filter { "PrivacyGuard" in it.text.replace(certificateName, "") || "PRIVACYGUARD" in it.text }
            .map { "${it.locale}/${it.file}: ${it.key}" }
        if (hits.isNotEmpty()) fail("Still says PrivacyGuard (the app is CoreLock):\n" + hits.joinToString("\n"))
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
