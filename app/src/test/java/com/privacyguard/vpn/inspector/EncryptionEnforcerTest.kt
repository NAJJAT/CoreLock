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
}
