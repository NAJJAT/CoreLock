package com.privacyguard.core.session

import org.junit.Assert.*
import org.junit.Test

class SessionKeyTest {

    private fun tcp(src: String = "10.0.0.1", srcPort: Int = 54321,
                    dst: String = "93.184.216.34", dstPort: Int = 443) =
        SessionKey.of(src, srcPort, dst, dstPort, SessionKey.PROTO_TCP)

    private fun udp(src: String = "10.0.0.1", srcPort: Int = 12345,
                    dst: String = "8.8.8.8", dstPort: Int = 53) =
        SessionKey.of(src, srcPort, dst, dstPort, SessionKey.PROTO_UDP)

    // ── equality ──────────────────────────────────────────────────────────────

    @Test
    fun two_identical_keys_are_equal() {
        val a = tcp()
        val b = tcp()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun keys_with_different_protocol_are_not_equal() {
        val t = tcp(srcPort = 80, dstPort = 80)
        val u = SessionKey.of("10.0.0.1", 80, "93.184.216.34", 80, SessionKey.PROTO_UDP)
        assertNotEquals(t, u)
    }

    @Test
    fun keys_with_different_ports_are_not_equal() {
        assertNotEquals(tcp(dstPort = 80), tcp(dstPort = 443))
    }

    @Test
    fun keys_with_different_ips_are_not_equal() {
        assertNotEquals(tcp(dst = "1.1.1.1"), tcp(dst = "8.8.8.8"))
    }

    // ── reversed ──────────────────────────────────────────────────────────────

    @Test
    fun reversed_swaps_source_and_destination() {
        val original = tcp(src = "10.0.0.1", srcPort = 54321,
                           dst = "93.184.216.34", dstPort = 443)
        val rev = original.reversed()
        assertEquals("93.184.216.34", rev.sourceIp)
        assertEquals(443,             rev.sourcePort)
        assertEquals("10.0.0.1",      rev.destinationIp)
        assertEquals(54321,           rev.destinationPort)
        assertEquals(SessionKey.PROTO_TCP, rev.protocol)
    }

    @Test
    fun double_reversed_equals_original() {
        val key = tcp()
        assertEquals(key, key.reversed().reversed())
    }

    // ── derived properties ────────────────────────────────────────────────────

    @Test
    fun isHttpCleartext_is_true_for_port_80() {
        assertTrue(tcp(dstPort = 80).isHttpCleartext)
    }

    @Test
    fun isHttpCleartext_is_true_for_port_8080() {
        assertTrue(tcp(dstPort = 8080).isHttpCleartext)
    }

    @Test
    fun isHttpCleartext_is_false_for_port_443() {
        assertFalse(tcp(dstPort = 443).isHttpCleartext)
    }

    @Test
    fun isTls_is_true_for_port_443() {
        assertTrue(tcp(dstPort = 443).isTls)
    }

    @Test
    fun isTls_is_true_for_port_8443() {
        assertTrue(tcp(dstPort = 8443).isTls)
    }

    @Test
    fun isTls_is_false_for_port_80() {
        assertFalse(tcp(dstPort = 80).isTls)
    }

    @Test
    fun isDns_is_true_for_udp_port_53() {
        assertTrue(udp(dstPort = 53).isDns)
    }

    @Test
    fun isDns_is_false_for_tcp_port_53() {
        assertFalse(tcp(dstPort = 53).isDns)
    }

    @Test
    fun isDns_is_false_for_udp_non_dns_port() {
        assertFalse(udp(dstPort = 443).isDns)
    }

    // ── protocolName ─────────────────────────────────────────────────────────

    @Test
    fun protocolName_is_TCP_for_proto_6() {
        assertEquals("TCP", tcp().protocolName)
    }

    @Test
    fun protocolName_is_UDP_for_proto_17() {
        assertEquals("UDP", udp().protocolName)
    }

    @Test
    fun protocolName_is_ICMP_for_proto_1() {
        val key = SessionKey.of("1.2.3.4", 0, "5.6.7.8", 0, SessionKey.PROTO_ICMP)
        assertEquals("ICMP", key.protocolName)
    }

    @Test
    fun protocolName_shows_number_for_unknown_protocol() {
        val key = SessionKey.of("1.2.3.4", 0, "5.6.7.8", 0, 47)
        assertEquals("PROTO(47)", key.protocolName)
    }

    // ── toString ─────────────────────────────────────────────────────────────

    @Test
    fun toString_contains_all_five_fields() {
        val key = tcp(src = "10.0.0.1", srcPort = 54321,
                      dst = "93.184.216.34", dstPort = 443)
        val s = key.toString()
        assertTrue(s.contains("10.0.0.1"))
        assertTrue(s.contains("54321"))
        assertTrue(s.contains("93.184.216.34"))
        assertTrue(s.contains("443"))
        assertTrue(s.contains("TCP"))
    }
}
