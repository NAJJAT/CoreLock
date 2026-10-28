package com.privacyguard.vpn.dualvpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Dual VPN text said neither server knows both who you are and where you go.
 * SOCKS5 is not encrypted and the second CONNECT passes through the first server,
 * so server 1 sees your IP, every destination and your server 2 login. The text
 * must say so, in every language, and must not claim the opposite.
 */
class DualVpnTextTest {

    private val langs = listOf("values", "values-ar", "values-fr", "values-nl")
    private val names = listOf("sec_dual_desc", "set_dual_desc", "sec_hop_1_desc", "sec_hop_net_desc")

    private fun strings(lang: String): Map<String, String> =
        File("src/main/res/$lang").listFiles { f -> f.name.startsWith("strings") }!!
            .flatMap { Regex("""<string name="([^"]+)">(.*?)</string>""").findAll(it.readText()).toList() }
            .associate { it.groupValues[1] to it.groupValues[2] }

    /** Old claims, in each language, that no single server sees both ends. */
    private val falseClaims = listOf(
        "knows both", "Neither server", "not your destination", "full picture",
        "à la fois qui vous êtes", "vue complète", "pas votre destination",
        "wie je bent én", "volledige beeld", "niet je bestemming",
        "هويتك ووجهتك معًا", "الصورة الكاملة", "لا وجهتك",
    )

    @Test
    fun no_language_claims_that_no_server_sees_both_ends() {
        for (lang in langs) {
            val s = strings(lang)
            for (name in names) {
                val text = s[name] ?: error("$name missing in $lang")
                for (claim in falseClaims) assertFalse("$lang/$name: \"$claim\"", text.contains(claim))
            }
        }
    }

    @Test
    fun the_text_says_socks5_is_not_encrypted_and_https_stays_encrypted() {
        val en = strings("values")
        for (name in listOf("sec_dual_desc", "set_dual_desc")) {
            assertTrue(name, en.getValue(name).contains("not encrypted"))
            assertTrue(name, en.getValue(name).contains("HTTPS"))
            assertTrue(name, en.getValue(name).contains("server 2 login"))
        }
        for (lang in langs) {
            for (name in listOf("sec_dual_desc", "set_dual_desc")) {
                assertTrue("$lang/$name", strings(lang).getValue(name).contains("HTTPS"))
            }
        }
    }

    @Test
    fun the_code_comment_does_not_claim_it_either() {
        val src = File("src/main/java/com/privacyguard/vpn/dualvpn/DualVpnTunnel.kt").readText()
        assertFalse(src.contains("but not the target"))
        assertTrue(src.contains("SOCKS5 is not encrypted"))
    }
}
