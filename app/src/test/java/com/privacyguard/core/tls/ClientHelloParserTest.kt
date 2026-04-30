package com.privacyguard.core.tls

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ClientHelloParser.parse() using hand-crafted raw TLS record bytes.
 *
 * The existing Ja3FingerprintTest tests ClientHello objects directly; these tests
 * exercise the full binary parsing path from real (or simulated) wire bytes.
 */
class ClientHelloParserTest {

    // ── byte-level builder helpers ────────────────────────────────────────────

    private fun u8(v: Int)  = byteArrayOf(v.toByte())
    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun buildSniExtension(hostname: String): ByteArray {
        val name    = hostname.toByteArray(Charsets.UTF_8)
        val entry   = u8(0) + u16(name.size) + name               // type=host_name, length, name
        val list    = u16(entry.size) + entry                      // server name list
        return u16(0x0000) + u16(list.size) + list                 // ext type + ext length + data
    }

    private fun buildGroupsExtension(groups: List<Int>): ByteArray {
        val listBytes = groups.flatMap { u16(it).toList() }.toByteArray()
        val data      = u16(listBytes.size) + listBytes
        return u16(0x000a) + u16(data.size) + data
    }

    private fun buildPointFmtsExtension(fmts: List<Int>): ByteArray {
        val data = u8(fmts.size) + fmts.map { it.toByte() }.toByteArray()
        return u16(0x000b) + u16(data.size) + data
    }

    private fun buildAlpnExtension(protocols: List<String>): ByteArray {
        val entries = protocols.flatMap { p ->
            val b = p.toByteArray(Charsets.UTF_8)
            (u8(b.size) + b).toList()
        }.toByteArray()
        val data = u16(entries.size) + entries
        return u16(0x0010) + u16(data.size) + data
    }

    private fun buildSupportedVersionsExtension(versions: List<Int>): ByteArray {
        val listBytes = versions.flatMap { u16(it).toList() }.toByteArray()
        val data      = u8(listBytes.size) + listBytes
        return u16(0x002b) + u16(data.size) + data
    }

    /**
     * Assembles a complete TLS 1.2 record wrapping a ClientHello.
     * All lengths are computed automatically.
     */
    private fun buildClientHelloRecord(
        clientVersion: Int      = 0x0303,
        ciphers: List<Int>      = listOf(0xC02B, 0xC02C, 0xC013),
        sni: String?            = "example.com",
        groups: List<Int>       = listOf(0x001D, 0x0017, 0x0018),
        pointFmts: List<Int>    = listOf(0),
        alpn: List<String>      = listOf("h2"),
        supportedVersions: List<Int> = listOf(0x0304),
    ): ByteArray {
        val random = ByteArray(32)

        val cipherBytes = ciphers.flatMap { u16(it).toList() }.toByteArray()
        val extensions  = buildList {
            if (sni != null)        add(buildSniExtension(sni))
            add(buildGroupsExtension(groups))
            add(buildPointFmtsExtension(pointFmts))
            add(buildAlpnExtension(alpn))
            add(buildSupportedVersionsExtension(supportedVersions))
        }.fold(ByteArray(0)) { a, b -> a + b }

        val body =
            u16(clientVersion) +         // client version
            random +                      // 32-byte random
            u8(0) +                       // session ID length = 0
            u16(cipherBytes.size) +       // cipher suites length
            cipherBytes +
            u8(1) + u8(0) +              // 1 compression method: null
            u16(extensions.size) +        // extensions length
            extensions

        val handshake = u8(0x01) + u24(body.size) + body   // ClientHello type + 3-byte length
        return u8(0x16) + u16(0x0303) + u16(handshake.size) + handshake
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    fun parse_valid_clienthello_returns_non_null() {
        val bytes = buildClientHelloRecord()
        assertNotNull(ClientHelloParser.parse(bytes))
    }

    @Test
    fun parse_extracts_client_version() {
        val bytes = buildClientHelloRecord(clientVersion = 0x0303)
        val hello = ClientHelloParser.parse(bytes)!!
        assertEquals(0x0303, hello.tlsVersion)
    }

    @Test
    fun parse_extracts_cipher_suites() {
        val ciphers = listOf(0xC02B, 0xC013)
        val bytes   = buildClientHelloRecord(ciphers = ciphers)
        val hello   = ClientHelloParser.parse(bytes)!!
        assertEquals(ciphers, hello.cipherSuites)
    }

    @Test
    fun parse_extracts_sni() {
        val bytes = buildClientHelloRecord(sni = "api.github.com")
        val hello = ClientHelloParser.parse(bytes)!!
        assertEquals("api.github.com", hello.sni)
    }

    @Test
    fun parse_extracts_supported_groups() {
        val groups = listOf(0x001D, 0x0017)
        val bytes  = buildClientHelloRecord(groups = groups)
        val hello  = ClientHelloParser.parse(bytes)!!
        assertEquals(groups, hello.supportedGroups)
    }

    @Test
    fun parse_extracts_ec_point_formats() {
        val bytes = buildClientHelloRecord(pointFmts = listOf(0))
        val hello = ClientHelloParser.parse(bytes)!!
        assertEquals(listOf(0), hello.ecPointFormats)
    }

    @Test
    fun parse_extracts_alpn_protocols() {
        val bytes = buildClientHelloRecord(alpn = listOf("h2", "http/1.1"))
        val hello = ClientHelloParser.parse(bytes)!!
        assertEquals(listOf("h2", "http/1.1"), hello.alpn)
    }

    @Test
    fun parse_extracts_supported_versions() {
        val bytes = buildClientHelloRecord(supportedVersions = listOf(0x0304))
        val hello = ClientHelloParser.parse(bytes)!!
        assertEquals(listOf(0x0304), hello.supportedVersions)
    }

    @Test
    fun parse_without_sni_returns_null_sni() {
        val bytes = buildClientHelloRecord(sni = null)
        val hello = ClientHelloParser.parse(bytes)!!
        assertNull(hello.sni)
    }

    @Test
    fun parse_without_extensions_still_returns_clienthello() {
        // Build a ClientHello with no extensions block at all
        val ciphers     = listOf(0xC013)
        val cipherBytes = u16(0xC013)
        val random      = ByteArray(32)
        val body =
            u16(0x0303) + random + u8(0) +
            u16(cipherBytes.size) + cipherBytes +
            u8(1) + u8(0)   // compression, no extensions
        val handshake = u8(0x01) + u24(body.size) + body
        val record    = u8(0x16) + u16(0x0303) + u16(handshake.size) + handshake

        val hello = ClientHelloParser.parse(record)
        assertNotNull(hello)
        assertEquals(listOf(0xC013), hello!!.cipherSuites)
        assertTrue(hello.extensions.isEmpty())
    }

    // ── JA3 round-trip ────────────────────────────────────────────────────────

    @Test
    fun parsed_clienthello_produces_valid_ja3_string() {
        val bytes = buildClientHelloRecord(
            clientVersion = 0x0303,
            ciphers       = listOf(0xC02B, 0xC013),
            groups        = listOf(0x001D, 0x0017),
            pointFmts     = listOf(0),
            sni           = "example.com",
            alpn          = listOf("h2"),
            supportedVersions = listOf(0x0304),
        )
        val hello  = ClientHelloParser.parse(bytes)!!
        val ja3str = hello.ja3String()
        // Format: version,ciphers,extensions,curves,pointFormats
        val parts = ja3str.split(",")
        assertEquals(5, parts.size)
        assertTrue(parts[0].isNotEmpty())   // version
        // JA3 hash must be a 32-char hex string
        assertEquals(32, hello.ja3Hash().length)
        assertTrue(hello.ja3Hash().all { it.isLetterOrDigit() })
    }

    @Test
    fun greased_cipher_suites_are_excluded_from_ja3() {
        // GREASE values: 0x0A0A, 0x1A1A, 0x2A2A, …
        val ciphers = listOf(0x0A0A, 0xC02B, 0x1A1A, 0xC013)
        val bytes   = buildClientHelloRecord(ciphers = ciphers)
        val hello   = ClientHelloParser.parse(bytes)!!
        val ja3str  = hello.ja3String()
        assertFalse(ja3str.contains("2570"))    // 0x0A0A decimal
        assertFalse(ja3str.contains("6682"))    // 0x1A1A decimal
        assertTrue(ja3str.contains("49195") || ja3str.contains("49171"))
    }

    // ── malformed inputs ──────────────────────────────────────────────────────

    @Test
    fun parse_returns_null_for_empty_array() {
        assertNull(ClientHelloParser.parse(ByteArray(0)))
    }

    @Test
    fun parse_returns_null_for_too_short_array() {
        assertNull(ClientHelloParser.parse(ByteArray(8)))   // min is 9
    }

    @Test
    fun parse_returns_null_for_non_handshake_record() {
        val bytes = ByteArray(20)
        bytes[0] = 0x17.toByte()   // Application Data, not Handshake (0x16)
        assertNull(ClientHelloParser.parse(bytes))
    }

    @Test
    fun parse_returns_null_for_non_clienthello_handshake() {
        val bytes = buildClientHelloRecord()
        val bad   = bytes.copyOf()
        bad[5] = 0x02.toByte()   // Change ClientHello (0x01) to ServerHello (0x02)
        assertNull(ClientHelloParser.parse(bad))
    }

    @Test
    fun parse_returns_null_for_truncated_random_field() {
        val bytes = buildClientHelloRecord()
        // Keep only up to the random field start, missing some random bytes
        assertNull(ClientHelloParser.parse(bytes.copyOf(20)))
    }
}
