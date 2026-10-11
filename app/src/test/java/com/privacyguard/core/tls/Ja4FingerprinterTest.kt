package com.privacyguard.core.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

class Ja4FingerprinterTest {

    // The Chrome example from the FoxIO JA4 technical details; expected value is
    // the spec's own: t13d1516h2_8daaf6152771_e5627efa2ab1.
    private val chromeCiphers = listOf(
        0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9,
        0xcca8, 0xc013, 0xc014, 0x009c, 0x009d, 0x002f, 0x0035,
    )
    private val chromeExtensions = listOf(
        0x001b, 0x0000, 0x0033, 0x0010, 0x4469, 0x0017, 0x002d, 0x000d,
        0x0005, 0x0023, 0x0012, 0x002b, 0xff01, 0x000b, 0x000a, 0x0015,
    )
    private val chromeSigAlgs = listOf(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601)

    private fun chrome(
        ciphers: List<Int> = chromeCiphers,
        extensions: List<Int> = chromeExtensions,
        alpn: List<String> = listOf("h2", "http/1.1"),
    ) = ClientHello(
        tlsVersion = 0x0303,
        cipherSuites = ciphers,
        compressionMethods = listOf(0),
        extensions = extensions,
        supportedGroups = listOf(0x001d, 0x0017, 0x0018),
        ecPointFormats = listOf(0),
        sni = "example.com",
        alpn = alpn,
        supportedVersions = listOf(0x0304, 0x0303),
        signatureAlgorithms = chromeSigAlgs,
    )

    @Test
    fun matches_spec_reference_fingerprint() {
        assertEquals("t13d1516h2_8daaf6152771_e5627efa2ab1", Ja4Fingerprinter.ja4(chrome()))
    }

    @Test
    fun extension_order_does_not_change_fingerprint() {
        // Chrome shuffles extension order per connection; JA4 must not care (JA3 does).
        val shuffled = chrome(extensions = chromeExtensions.reversed())
        assertEquals(Ja4Fingerprinter.ja4(chrome()), Ja4Fingerprinter.ja4(shuffled))
        assertNotEquals(chrome().ja3Hash(), shuffled.ja3Hash())
    }

    @Test
    fun grease_values_are_ignored() {
        val greased = chrome(
            ciphers = listOf(0x0a0a) + chromeCiphers,
            extensions = listOf(0x1a1a) + chromeExtensions + 0xfafa,
        )
        assertEquals(Ja4Fingerprinter.ja4(chrome()), Ja4Fingerprinter.ja4(greased))
    }

    @Test
    fun no_sni_and_no_alpn_are_encoded() {
        val hello = chrome(extensions = chromeExtensions - 0x0000 - 0x0010, alpn = emptyList())
            .copy(sni = null)
        assertEquals("t13i151400", Ja4Fingerprinter.ja4(hello).substringBefore('_'))
    }

    @Test
    fun http11_alpn_is_h1() {
        assertEquals("t13d1516h1", Ja4Fingerprinter.ja4(chrome(alpn = listOf("http/1.1"))).substringBefore('_'))
    }

    @Test
    fun fingerprint_is_independent_of_default_locale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("t13d1516h2_8daaf6152771_e5627efa2ab1", Ja4Fingerprinter.ja4(chrome()))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
