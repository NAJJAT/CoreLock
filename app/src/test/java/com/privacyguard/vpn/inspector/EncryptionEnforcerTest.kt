package com.privacyguard.vpn.inspector

import com.privacyguard.core.metadata.EncryptionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class EncryptionEnforcerTest {

    private val enforcer = EncryptionEnforcer()
    private fun classify(port: Int, data: ByteArray) = enforcer.classify(port, data).encryptionStatus

    // TLS record header + ClientHello handshake type, version TLS 1.2.
    private val clientHello = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x30, 0x01, 0x00, 0x00, 0x2c, 0x03, 0x03) +
        ByteArray(40)

    @Test
    fun plainHttpIsCleartextOnAnyPort() {
        assertEquals(EncryptionStatus.CLEARTEXT, classify(80, "GET / HTTP/1.1\r\n".toByteArray()))
        assertEquals(EncryptionStatus.CLEARTEXT, classify(8000, "POST /api HTTP/1.1\r\n".toByteArray()))
    }

    @Test
    fun tlsHandshakeIsNotCleartextEvenOnPort80() {
        assertEquals(false, classify(80, clientHello) == EncryptionStatus.CLEARTEXT)
    }

    /** WhatsApp's own encrypted protocol on 5222 or 80 is opaque, not plaintext. */
    @Test
    fun unrecognisedBinaryProtocolIsUnknown() {
        val noise = byteArrayOf(0x57, 0x41, 0x06, 0x02, 0x00, 0x00, 0x24, 0x12, 0x22, 0x0a)
        assertEquals(EncryptionStatus.UNKNOWN, classify(5222, noise))
        assertEquals(EncryptionStatus.UNKNOWN, classify(80, noise))
    }
}
