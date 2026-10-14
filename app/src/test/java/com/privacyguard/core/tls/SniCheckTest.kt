package com.privacyguard.core.tls

import com.privacyguard.core.utils.Hostnames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for V-06: SNI validation, SNI/DNS mismatch, and escaping for display. */
class SniCheckTest {

    @Test fun only_real_hostnames_are_accepted_as_sni() {
        assertEquals("www.example.com", Hostnames.validSni("WWW.Example.com."))
        assertEquals("_dmarc.example.com", Hostnames.validSni("_dmarc.example.com"))
        listOf(
            "‮moc.elgoog", "=HYPERLINK(\"x\")", "<script>a.b</script>", "1.2.3.4", "localhost",
            "a..b", "-a.example.com", "a b.example", "x".repeat(64) + ".com", "exаmple.com",
        ).forEach { assertNull("accepted '$it'", Hostnames.validSni(it)) }
    }

    @Test fun parser_rejects_a_non_hostname_sni_and_says_so() {
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        val name = "‮moc.elgoog".toByteArray(Charsets.UTF_8)
        val sni = u16(0) + u16(name.size + 5) + u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + u16(0x1301) + byteArrayOf(1, 0) + u16(sni.size) + sni
        val hs = byteArrayOf(1, 0) + u16(body.size) + body
        val hello = ClientHelloParser.parse(byteArrayOf(0x16, 3, 1) + u16(hs.size) + hs)!!
        assertNull(hello.sni)
        assertTrue(hello.sniRejected)
        assertNull(com.privacyguard.vpn.inspector.EncryptionEnforcer().inspect(443, byteArrayOf(0x16, 3, 1) + u16(hs.size) + hs).sniHostname)
    }

    @Test fun mismatch_only_when_the_claimed_site_was_never_looked_up() {
        // App resolved tracker.evil and claims google.com without looking it up.
        val f = SniCheck.evaluate("www.google.com", false, dnsNameForIp = "c2.tracker.evil", sniWasResolved = false)
        assertEquals(SniCheck.Kind.SNI_MISMATCH, f?.kind)
        // Same site, other host (CDN shard): fine.
        assertNull(SniCheck.evaluate("img.example.com", false, "www.example.com", false))
        // Shared CDN address, but the app did look the name up: fine.
        assertNull(SniCheck.evaluate("www.google.com", false, "other.site", sniWasResolved = true))
        // Nothing known about the address: fine.
        assertNull(SniCheck.evaluate("www.google.com", false, null, false))
        assertEquals(SniCheck.Kind.INVALID_SNI, SniCheck.evaluate(null, true, null, false)?.kind)
    }

    @Test fun mismatched_session_is_recorded_under_the_dns_name() {
        val s = com.privacyguard.core.session.Session(com.privacyguard.core.session.SessionKey.of("10.0.0.2", 1, "1.2.3.4", 443, 6))
        s.tlsSni = "www.google.com"; s.resolvedHostname = "c2.tracker.evil"
        assertEquals("www.google.com", s.hostname)
        s.sniMismatch = true
        assertEquals("c2.tracker.evil", s.hostname)
    }

    @Test fun display_escapes_everything_but_printable_ascii() {
        assertEquals("\\u{202e}moc.elgoog", Hostnames.display("‮moc.elgoog"))
        assertEquals("a\\u{0}b", Hostnames.display("a\u0000b"))
        assertTrue(Hostnames.display("x".repeat(1000)).length <= 254)
    }

    @Test fun labels_keep_scripts_but_lose_bidi_and_controls() {
        assertEquals("واتساب", Hostnames.label("واتساب"))
        assertEquals("Bank gpj.exe", Hostnames.label("Bank ‮gpj.exe‬"))
        assertFalse(Hostnames.label("a\u0007b⁦c").any { it == '\u0007' || it == '⁦' })
    }
}
