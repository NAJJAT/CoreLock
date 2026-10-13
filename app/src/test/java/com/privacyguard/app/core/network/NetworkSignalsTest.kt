package com.privacyguard.app.core.network

import com.privacyguard.core.packet.DnsPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSignalsTest {

    // ── Tampered DNS answers ────────────────────────────────────────────────

    @Test
    fun public_name_answered_with_a_private_or_null_address_looks_tampered() {
        assertTrue(NetworkSignals.looksTampered("www.bank.com", listOf("192.168.1.1")))
        assertTrue(NetworkSignals.looksTampered("www.bank.com", listOf("0.0.0.0")))
        assertTrue(NetworkSignals.looksTampered("www.bank.com", listOf("93.184.215.14", "10.0.0.5")))
    }

    @Test
    fun normal_answers_and_private_by_design_names_do_not() {
        assertFalse(NetworkSignals.looksTampered("www.bank.com", listOf("93.184.215.14")))
        assertFalse(NetworkSignals.looksTampered("www.bank.com", emptyList()))
        listOf("fritz.box", "nas.home.arpa", "printer.local", "router.lan", "abc.plex.direct", "10-0-0-1.nip.io", "intranet")
            .forEach { assertFalse(it, NetworkSignals.looksTampered(it, listOf("192.168.0.1"))) }
    }

    // ── DNS hijacking ────────────────────────────────────────────────────────

    private fun response(query: ByteArray, answerIp: String?): DnsPacket {
        val q = DnsPacket.parse(query)!!
        if (answerIp == null) return DnsPacket.parse(DnsPacket.buildBlockedResponse(q))!!   // NXDOMAIN
        // Header (response, 1 question, 1 answer) + question + one A record.
        val out = java.io.ByteArrayOutputStream()
        out.write(query, 0, 2); out.write(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0))
        out.write(query, 12, query.size - 12)
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
        answerIp.split('.').forEach { out.write(it.toInt()) }
        return DnsPacket.parse(out.toByteArray())!!
    }

    @Test
    fun the_probe_name_is_under_dot_invalid() {
        assertTrue(NetworkSignals.hijackProbeName("abc123").endsWith(".invalid"))
    }

    @Test
    fun an_address_for_a_name_that_cannot_exist_is_hijacking() {
        val q = DnsPacket.buildQuery(NetworkSignals.hijackProbeName("abc"), 77)
        assertEquals(true, NetworkSignals.isHijacked(response(q, "198.51.100.7")))
        assertEquals(false, NetworkSignals.isHijacked(response(q, null)))
        assertNull(NetworkSignals.isHijacked(null))
    }

    // ── TLS interception ─────────────────────────────────────────────────────

    @Test
    fun an_untrusted_certificate_is_interception_but_a_timeout_is_not() {
        val untrusted = javax.net.ssl.SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")
        assertTrue(NetworkSignals.isCertificateRejection(untrusted))
        assertTrue(NetworkSignals.isCertificateRejection(RuntimeException(java.security.cert.CertificateException("bad"))))
        assertFalse(NetworkSignals.isCertificateRejection(java.net.SocketTimeoutException("timeout")))
        assertFalse(NetworkSignals.isCertificateRejection(java.io.IOException("connection reset")))
    }
}
