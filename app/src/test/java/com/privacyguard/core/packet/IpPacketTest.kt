package com.privacyguard.core.packet

import org.junit.Assert.*
import org.junit.Test

class IpPacketTest {

    // ── build + parse round-trip ───────────────────────────────────────────────

    @Test
    fun build_and_parse_tcp_packet_round_trips() {
        val payload = ByteArray(20) { it.toByte() }   // 20 bytes of dummy TCP
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "10.0.0.1", "93.184.216.34", payload)
        val ip = IpPacket.parse(raw)!!
        assertEquals(4,               ip.version)
        assertEquals(20,              ip.headerLength)
        assertEquals(IpPacket.PROTO_TCP, ip.protocol)
        assertEquals("10.0.0.1",      ip.sourceIp)
        assertEquals("93.184.216.34", ip.destinationIp)
        assertEquals(raw.size,        ip.totalLength)
        assertArrayEquals(payload,    ip.payload)
    }

    @Test
    fun build_and_parse_udp_packet_round_trips() {
        val payload = byteArrayOf(0, 53, 0, 80, 0, 8, 0, 0)   // minimal UDP header
        val raw = IpPacket.build(IpPacket.PROTO_UDP, "192.168.1.1", "8.8.8.8", payload)
        val ip = IpPacket.parse(raw)!!
        assertEquals(IpPacket.PROTO_UDP, ip.protocol)
        assertEquals("192.168.1.1",     ip.sourceIp)
        assertEquals("8.8.8.8",         ip.destinationIp)
    }

    // ── flags ─────────────────────────────────────────────────────────────────

    @Test
    fun df_flag_is_set_when_requested() {
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8", ByteArray(20), df = true)
        val ip = IpPacket.parse(raw)!!
        assertTrue(ip.flagDf)
        assertFalse(ip.flagMf)
    }

    @Test
    fun df_flag_is_clear_by_default() {
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8", ByteArray(20))
        val ip = IpPacket.parse(raw)!!
        assertFalse(ip.flagDf)
    }

    // ── malformed inputs ──────────────────────────────────────────────────────

    @Test
    fun parse_returns_null_for_empty_array() {
        assertNull(IpPacket.parse(ByteArray(0)))
    }

    @Test
    fun parse_returns_null_for_too_short_array() {
        assertNull(IpPacket.parse(ByteArray(19)))   // min is 20
    }

    @Test
    fun parse_returns_null_for_wrong_version() {
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8", ByteArray(20))
        val bad = raw.copyOf()
        bad[0] = 0x60.toByte()   // version = 6 (IPv6)
        assertNull(IpPacket.parse(bad))
    }

    @Test
    fun parse_returns_null_when_total_length_exceeds_array() {
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8", ByteArray(20))
        // Inflate total length to be larger than the actual byte array
        val bad = raw.copyOf()
        bad[2] = 0x7f.toByte()
        bad[3] = 0xff.toByte()
        assertNull(IpPacket.parse(bad))
    }

    // ── payload offset / length ───────────────────────────────────────────────

    @Test
    fun payload_offset_is_20_for_minimal_header() {
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8", ByteArray(20))
        val ip = IpPacket.parse(raw)!!
        assertEquals(20, ip.payloadOffset)
        assertEquals(20, ip.payloadLength)
    }

    @Test
    fun isUnfragmented_is_true_when_df_set_and_no_offset() {
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8", ByteArray(20), df = true)
        val ip = IpPacket.parse(raw)!!
        assertTrue(ip.isUnfragmented)
    }

    // ── withSourceIp / withDestinationIp ─────────────────────────────────────

    @Test
    fun withSourceIp_changes_source_and_reparses_correctly() {
        val raw = IpPacket.build(IpPacket.PROTO_UDP, "1.2.3.4", "8.8.8.8", ByteArray(8))
        val ip0 = IpPacket.parse(raw)!!
        val newRaw = ip0.withSourceIp("10.0.0.99")
        val ip1 = IpPacket.parse(newRaw)!!
        assertEquals("10.0.0.99", ip1.sourceIp)
        assertEquals("8.8.8.8",   ip1.destinationIp)
    }

    @Test
    fun withDestinationIp_changes_destination_and_reparses_correctly() {
        val raw = IpPacket.build(IpPacket.PROTO_UDP, "1.2.3.4", "8.8.8.8", ByteArray(8))
        val ip0 = IpPacket.parse(raw)!!
        val newRaw = ip0.withDestinationIp("172.16.0.1")
        val ip1 = IpPacket.parse(newRaw)!!
        assertEquals("1.2.3.4",    ip1.sourceIp)
        assertEquals("172.16.0.1", ip1.destinationIp)
    }

    // ── TTL decrement ─────────────────────────────────────────────────────────

    @Test
    fun withDecrementedTtl_reduces_ttl_by_one() {
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8", ByteArray(20), ttl = 64)
        val ip0 = IpPacket.parse(raw)!!
        val ip1 = IpPacket.parse(ip0.withDecrementedTtl())!!
        assertEquals(63, ip1.ttl)
    }

    @Test
    fun withDecrementedTtl_does_not_go_below_zero() {
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8", ByteArray(20), ttl = 0)
        val ip0 = IpPacket.parse(raw)!!
        val ip1 = IpPacket.parse(ip0.withDecrementedTtl())!!
        assertEquals(0, ip1.ttl)
    }
}
