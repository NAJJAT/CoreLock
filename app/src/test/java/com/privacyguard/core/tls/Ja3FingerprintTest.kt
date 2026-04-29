package com.privacyguard.core.tls

import org.junit.Assert.*
import org.junit.Test

class Ja3FingerprintTest {

    // Build a ClientHello with fully controlled fields so tests are deterministic
    private fun hello(
        version:    Int          = 771,   // TLS 1.2 = 0x0303
        ciphers:    List<Int>    = listOf(49195, 49196),
        extensions: List<Int>    = listOf(0, 23),
        groups:     List<Int>    = listOf(29, 23, 24),
        pointFmts:  List<Int>    = listOf(0),
        sni:        String?      = "example.com",
    ) = ClientHello(
        tlsVersion         = version,
        cipherSuites       = ciphers,
        compressionMethods = listOf(0),
        extensions         = extensions,
        supportedGroups    = groups,
        ecPointFormats     = pointFmts,
        sni                = sni,
        alpn               = listOf("h2"),
        supportedVersions  = listOf(772),
    )

    // ── JA3 string format ─────────────────────────────────────────────────────

    @Test
    fun ja3_string_has_five_comma_delimited_parts() {
        val parts = hello().ja3String().split(',')
        assertEquals(5, parts.size)
    }

    @Test
    fun ja3_string_encodes_fields_correctly() {
        val s = hello().ja3String()
        assertEquals("771",         s.split(',')[0])  // version
        assertEquals("49195-49196", s.split(',')[1])  // ciphers
        assertEquals("0-23",        s.split(',')[2])  // extensions
        assertEquals("29-23-24",    s.split(',')[3])  // groups
        assertEquals("0",           s.split(',')[4])  // point formats
    }

    @Test
    fun empty_field_produces_empty_segment() {
        val s = hello(groups = emptyList(), pointFmts = emptyList()).ja3String()
        val parts = s.split(',')
        assertEquals("", parts[3])
        assertEquals("", parts[4])
    }

    // ── GREASE filtering ──────────────────────────────────────────────────────

    @Test
    fun grease_values_are_filtered_from_cipher_list() {
        val grease = 0x0a0a   // hi == lo == 0x0A, (lo & 0x0F) == 0x0A
        val s = hello(ciphers = listOf(grease, 49195)).ja3String()
        assertFalse("GREASE should be stripped from JA3", s.contains(grease.toString()))
        assertTrue(s.contains("49195"))
    }

    @Test
    fun grease_values_filtered_from_extensions() {
        val grease = 0x1a1a
        val normal = 0
        val s = hello(extensions = listOf(grease, normal)).ja3String()
        assertFalse(s.split(',')[2].contains(grease.toString()))
        assertTrue(s.split(',')[2].contains(normal.toString()))
    }

    // ── JA3 hash ──────────────────────────────────────────────────────────────

    @Test
    fun ja3_hash_is_32_lowercase_hex_chars() {
        val h = hello().ja3Hash()
        assertEquals(32, h.length)
        assertTrue("Hash must be lowercase hex", h.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun ja3_hash_is_deterministic() {
        val h = hello()
        assertEquals(h.ja3Hash(), h.ja3Hash())
    }

    @Test
    fun different_cipher_suites_produce_different_hash() {
        val h1 = hello(ciphers = listOf(49195)).ja3Hash()
        val h2 = hello(ciphers = listOf(49196)).ja3Hash()
        assertNotEquals(h1, h2)
    }

    @Test
    fun different_versions_produce_different_hash() {
        val h1 = hello(version = 769).ja3Hash()  // TLS 1.0
        val h2 = hello(version = 771).ja3Hash()  // TLS 1.2
        assertNotEquals(h1, h2)
    }

    // ── Threat detection ──────────────────────────────────────────────────────

    @Test
    fun is_threat_true_for_cobalt_strike_hash() {
        assertTrue(Ja3Fingerprinter.isThreat("6734f37431670b3ab4292b8f60f29984"))
    }

    @Test
    fun is_threat_true_for_emotet_hash() {
        assertTrue(Ja3Fingerprinter.isThreat("cda2e0b53bc8d4d9d3cf4945fcad8e4c"))
    }

    @Test
    fun is_threat_false_for_unknown_hash() {
        assertFalse(Ja3Fingerprinter.isThreat("00000000000000000000000000000000"))
    }

    @Test
    fun inspect_hash_returns_alert_for_known_threat() {
        val hash  = "6734f37431670b3ab4292b8f60f29984" // Cobalt Strike
        val alert = Ja3Fingerprinter.inspectHash(hash, hello(), "com.victim.app")
        assertNotNull(alert)
        assertEquals(hash,                 alert!!.hash)
        assertEquals("Cobalt Strike Beacon", alert.malwareName)
        assertEquals("C2",                alert.category)
        assertEquals(10,                  alert.severity)
        assertEquals("com.victim.app",    alert.packageName)
        assertEquals("example.com",       alert.sni)
    }

    @Test
    fun inspect_hash_returns_null_for_unknown_hash() {
        assertNull(Ja3Fingerprinter.inspectHash("ffffffffffffffffffffffffffffffff", hello(), null))
    }

    @Test
    fun inspect_uses_live_hash_from_hello() {
        val h = hello()
        val computedHash = h.ja3Hash()
        val alert = Ja3Fingerprinter.inspect(h, "pkg")
        if (Ja3Fingerprinter.isThreat(computedHash)) {
            assertNotNull(alert)
        } else {
            assertNull(alert)
        }
    }

    @Test
    fun hash_of_returns_same_as_ja3_hash_on_hello() {
        val h = hello()
        assertEquals(h.ja3Hash(), Ja3Fingerprinter.hashOf(h))
    }

    // ── Alert fields ──────────────────────────────────────────────────────────

    @Test
    fun alert_timestamp_is_recent() {
        val before = System.currentTimeMillis()
        val alert  = Ja3Fingerprinter.inspectHash(
            "6734f37431670b3ab4292b8f60f29984", hello(), null)
        val after  = System.currentTimeMillis()
        assertNotNull(alert)
        assertTrue(alert!!.timestamp in before..after)
    }

    @Test
    fun alert_sni_is_null_when_hello_has_no_sni() {
        val alert = Ja3Fingerprinter.inspectHash(
            "6734f37431670b3ab4292b8f60f29984", hello(sni = null), null)
        assertNotNull(alert)
        assertNull(alert!!.sni)
    }
}
