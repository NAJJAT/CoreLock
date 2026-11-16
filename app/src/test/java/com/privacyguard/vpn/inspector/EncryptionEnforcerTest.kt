package com.privacyguard.vpn.inspector

import com.privacyguard.core.metadata.EncryptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionEnforcerTest {

    private val enforcer = EncryptionEnforcer()

    /** Minimal TLS record header + ClientHello type byte. */
    private val clientHello = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x40, 0x01) + ByteArray(64)

    @Test
    fun http_request_line_is_cleartext_on_any_port() {
        val get = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        assertEquals(EncryptionStatus.CLEARTEXT, enforcer.inspect(80, get).encryptionStatus)
        assertEquals(EncryptionStatus.CLEARTEXT, enforcer.inspect(8000, get).encryptionStatus)
    }

    @Test
    fun port_80_without_http_is_not_cleartext() {
        // WhatsApp's Noise handshake starts "WA" on port 80.
        val noise = byteArrayOf(0x57, 0x41, 0x06, 0x02) + ByteArray(32) { 0x7f }
        assertEquals(EncryptionStatus.UNKNOWN, enforcer.inspect(80, noise).encryptionStatus)
    }

    @Test
    fun no_data_is_unknown_not_cleartext() {
        assertEquals(EncryptionStatus.UNKNOWN, enforcer.inspect(80, ByteArray(0)).encryptionStatus)
        assertEquals(EncryptionStatus.UNKNOWN, enforcer.inspect(443, ByteArray(0)).encryptionStatus)
    }

    @Test
    fun port_443_without_a_client_hello_is_unknown() {
        assertEquals(EncryptionStatus.UNKNOWN, enforcer.inspect(443, byteArrayOf(1, 2, 3, 4, 5, 6, 7)).encryptionStatus)
    }

    @Test
    fun tls_client_hello_is_tls_on_any_port() {
        // Record version 3.1 with no supported_versions: classified as TLS, not cleartext/unknown.
        val s = enforcer.inspect(80, clientHello).encryptionStatus
        assertTrue("was $s", s != EncryptionStatus.UNKNOWN && s != EncryptionStatus.CLEARTEXT)
    }

    @Test
    fun quic_initial_is_quic() {
        // Long header + fixed bit, version 1.
        val v1 = byteArrayOf(0xC3.toByte(), 0x00, 0x00, 0x00, 0x01, 0x08, 0x00) + ByteArray(1200)
        assertEquals(EncryptionStatus.QUIC, enforcer.inspectUdp(v1))
        val v2 = byteArrayOf(0xC0.toByte(), 0x6b, 0x33, 0x43, 0xcf.toByte(), 0x08, 0x00)
        assertEquals(EncryptionStatus.QUIC, enforcer.inspectUdp(v2))
    }

    @Test
    fun other_udp_is_unknown() {
        assertEquals(EncryptionStatus.UNKNOWN, enforcer.inspectUdp(byteArrayOf(0x40, 1, 2, 3, 4, 5, 6, 7)))  // short header
        assertEquals(EncryptionStatus.UNKNOWN, enforcer.inspectUdp(byteArrayOf(0xC0.toByte(), 0x12, 0x34, 0x56, 0x78, 0, 0)))  // unknown version
        assertEquals(EncryptionStatus.UNKNOWN, enforcer.inspectUdp(ByteArray(3)))
    }

    /**
     * A minimal ClientHello: [record] is the version in the record header, [client] the
     * handshake's client_version. No extensions, so no supported_versions.
     */
    private fun hello(record: Int, client: Int): ByteArray {
        val body = byteArrayOf((client ushr 8).toByte(), client.toByte()) + ByteArray(32) +
            byteArrayOf(0, 0, 2, 0x00, 0x2F, 1, 0, 0, 0)   // no session id, 1 suite, null compression, no extensions
        val hs = byteArrayOf(0x01, 0, (body.size ushr 8).toByte(), body.size.toByte()) + body
        return byteArrayOf(0x16, (record ushr 8).toByte(), record.toByte(), (hs.size ushr 8).toByte(), hs.size.toByte()) + hs
    }

    // The record header often says 3.1 for compatibility (RFC 8446 §5.1): the version the
    // client offers is client_version in the handshake (or supported_versions).
    @Test
    fun tls12_client_with_a_compatibility_record_version_is_not_weak() {
        val r = enforcer.inspect(443, hello(record = 0x0301, client = 0x0303))
        assertEquals(EncryptionStatus.TLS_1_2, r.encryptionStatus)
    }

    @Test
    fun tls10_client_is_weak_whatever_the_record_says() {
        assertEquals(EncryptionStatus.WEAK_TLS, enforcer.inspect(443, hello(record = 0x0303, client = 0x0301)).encryptionStatus)
        assertEquals(EncryptionStatus.WEAK_TLS, enforcer.inspect(443, hello(record = 0x0301, client = 0x0302)).encryptionStatus)
    }

    @Test
    fun a_hello_cut_before_client_version_is_tls_of_unknown_version_not_weak() {
        val cut = hello(record = 0x0301, client = 0x0301).copyOf(9)
        assertEquals(EncryptionStatus.TLS, enforcer.inspect(443, cut).encryptionStatus)
    }
}
