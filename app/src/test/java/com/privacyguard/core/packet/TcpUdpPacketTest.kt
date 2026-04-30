package com.privacyguard.core.packet

import org.junit.Assert.*
import org.junit.Test

class TcpPacketTest {

    private fun makeTcpIp(
        srcPort: Int = 54321,
        dstPort: Int = 443,
        seqNum: Long = 1L,
        flags: String = "SYN",
        data: ByteArray = ByteArray(0),
    ): IpPacket {
        val tcp = TcpPacket(
            sourcePort           = srcPort,
            destinationPort      = dstPort,
            sequenceNumber       = seqNum,
            acknowledgmentNumber = 0L,
            headerLength         = 20,
            flagNs = false, flagCwr = false, flagEce = false, flagUrg = false,
            flagAck = "ACK" in flags,
            flagPsh = "PSH" in flags,
            flagRst = "RST" in flags,
            flagSyn = "SYN" in flags,
            flagFin = "FIN" in flags,
            windowSize           = 65535,
            checksum             = 0,
            urgentPointer        = 0,
            options              = ByteArray(0),
            data                 = data,
        )
        val rawIp = IpPacket.build(IpPacket.PROTO_TCP, "10.0.0.1", "93.184.216.34", tcp.toBytes())
        return IpPacket.parse(rawIp)!!
    }

    // ── round-trip ────────────────────────────────────────────────────────────

    @Test
    fun parse_from_ip_round_trips_ports_and_flags() {
        val ip  = makeTcpIp(srcPort = 12345, dstPort = 443, flags = "SYN")
        val tcp = TcpPacket.parse(ip)!!
        assertEquals(12345, tcp.sourcePort)
        assertEquals(443,   tcp.destinationPort)
        assertTrue(tcp.flagSyn)
        assertFalse(tcp.flagAck)
        assertTrue(tcp.isSyn)
        assertFalse(tcp.isSynAck)
    }

    @Test
    fun syn_ack_flags_detected_correctly() {
        val ip  = makeTcpIp(flags = "SYN ACK")
        val tcp = TcpPacket.parse(ip)!!
        assertTrue(tcp.flagSyn)
        assertTrue(tcp.flagAck)
        assertTrue(tcp.isSynAck)
        assertFalse(tcp.isSyn)
    }

    @Test
    fun fin_flag_detected_correctly() {
        val ip  = makeTcpIp(flags = "FIN ACK")
        val tcp = TcpPacket.parse(ip)!!
        assertTrue(tcp.flagFin)
        assertTrue(tcp.isFin)
    }

    @Test
    fun rst_flag_detected_correctly() {
        val ip  = makeTcpIp(flags = "RST")
        val tcp = TcpPacket.parse(ip)!!
        assertTrue(tcp.flagRst)
        assertTrue(tcp.isRst)
    }

    @Test
    fun data_payload_round_trips() {
        val payload = byteArrayOf(0x47, 0x45, 0x54, 0x20, 0x2f, 0x20)   // "GET / "
        val ip  = makeTcpIp(flags = "PSH ACK", data = payload)
        val tcp = TcpPacket.parse(ip)!!
        assertTrue(tcp.hasData)
        assertArrayEquals(payload, tcp.data)
    }

    @Test
    fun empty_data_gives_hasData_false() {
        val ip  = makeTcpIp(flags = "SYN")
        val tcp = TcpPacket.parse(ip)!!
        assertFalse(tcp.hasData)
        assertEquals(0, tcp.data.size)
    }

    @Test
    fun sequence_number_round_trips() {
        val ip  = makeTcpIp(seqNum = 0xDEADBEEFL)
        val tcp = TcpPacket.parse(ip)!!
        assertEquals(0xDEADBEEFL, tcp.sequenceNumber)
    }

    // ── factory builders ──────────────────────────────────────────────────────

    @Test
    fun buildSyn_creates_syn_only_packet() {
        val tcp = TcpPacket.buildSyn(srcPort = 40000, dstPort = 80, seqNum = 42L)
        assertTrue(tcp.flagSyn)
        assertFalse(tcp.flagAck)
        assertEquals(40000, tcp.sourcePort)
        assertEquals(80, tcp.destinationPort)
        assertEquals(42L, tcp.sequenceNumber)
    }

    @Test
    fun buildRst_creates_rst_packet() {
        val tcp = TcpPacket.buildRst(srcPort = 80, dstPort = 40000, seqNum = 1L)
        assertTrue(tcp.flagRst)
        assertFalse(tcp.flagSyn)
        assertEquals(0, tcp.windowSize)
    }

    // ── malformed inputs ──────────────────────────────────────────────────────

    @Test
    fun parse_returns_null_for_non_tcp_ip_packet() {
        val rawIp = IpPacket.build(IpPacket.PROTO_UDP, "1.2.3.4", "5.6.7.8", ByteArray(8))
        val ip = IpPacket.parse(rawIp)!!
        assertNull(TcpPacket.parse(ip))
    }

    @Test
    fun parse_returns_null_when_payload_too_short_for_tcp_header() {
        // Build an IP/TCP packet then truncate the TCP header
        val ip = makeTcpIp()
        val truncated = ip.rawPacket.copyOf(ip.payloadOffset + 10)   // only 10 of 20 TCP bytes
        val truncatedIp = IpPacket.parse(
            IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8",
                truncated.copyOfRange(ip.payloadOffset, truncated.size))
        )!!
        // Manually test the raw-bytes parse path
        val result = TcpPacket.parse(truncatedIp.rawPacket, truncatedIp.payloadOffset,
            truncatedIp.payloadOffset + 10)
        assertNull(result)
    }

    // ── QUIC port constant ────────────────────────────────────────────────────

    @Test
    fun https_port_constant_is_443() {
        assertEquals(443, TcpPacket.PORT_HTTPS)
    }
}

// ─────────────────────────────────────────────────────────────────────────────

class UdpPacketTest {

    private fun makeUdpIp(
        srcPort: Int = 54321,
        dstPort: Int = 53,
        data: ByteArray = ByteArray(0),
    ): IpPacket {
        val udpBytes = UdpPacket.build(srcPort, dstPort, data).toBytes()
        val rawIp = IpPacket.build(IpPacket.PROTO_UDP, "10.0.0.1", "8.8.8.8", udpBytes)
        return IpPacket.parse(rawIp)!!
    }

    // ── round-trip ────────────────────────────────────────────────────────────

    @Test
    fun parse_from_ip_round_trips_ports() {
        val ip  = makeUdpIp(srcPort = 44444, dstPort = 53)
        val udp = UdpPacket.parse(ip)!!
        assertEquals(44444, udp.sourcePort)
        assertEquals(53,    udp.destinationPort)
    }

    @Test
    fun data_payload_round_trips() {
        val data = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val ip  = makeUdpIp(data = data)
        val udp = UdpPacket.parse(ip)!!
        assertArrayEquals(data, udp.data)
    }

    @Test
    fun empty_data_gives_isEmpty_true() {
        val ip  = makeUdpIp()
        val udp = UdpPacket.parse(ip)!!
        assertTrue(udp.isEmpty)
    }

    // ── QUIC detection ────────────────────────────────────────────────────────

    @Test
    fun quic_port_constant_equals_443() {
        assertEquals(443, UdpPacket.PORT_QUIC)
    }

    @Test
    fun udp_443_is_potential_quic_traffic() {
        val ip  = makeUdpIp(srcPort = 12345, dstPort = 443)
        val udp = UdpPacket.parse(ip)!!
        assertEquals(443, udp.destinationPort)
        // This is the condition checked in PrivacyVpnService for QUIC blocking
        assertTrue(udp.destinationPort == UdpPacket.PORT_QUIC)
    }

    @Test
    fun dns_port_detected_correctly() {
        val ip  = makeUdpIp(dstPort = 53)
        val udp = UdpPacket.parse(ip)!!
        assertTrue(udp.isDns)
    }

    @Test
    fun non_dns_port_not_flagged_as_dns() {
        val ip  = makeUdpIp(dstPort = 443)
        val udp = UdpPacket.parse(ip)!!
        assertFalse(udp.isDns)
    }

    // ── malformed inputs ──────────────────────────────────────────────────────

    @Test
    fun parse_returns_null_for_non_udp_ip_packet() {
        val rawIp = IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8", ByteArray(20))
        val ip = IpPacket.parse(rawIp)!!
        assertNull(UdpPacket.parse(ip))
    }

    @Test
    fun parse_returns_null_for_payload_shorter_than_udp_header() {
        val result = UdpPacket.parse(byteArrayOf(0, 0, 0, 0, 0, 0, 0), 0, 7)
        assertNull(result)
    }

    // ── length field ─────────────────────────────────────────────────────────

    @Test
    fun length_field_is_header_plus_data() {
        val data = ByteArray(16)
        val ip  = makeUdpIp(data = data)
        val udp = UdpPacket.parse(ip)!!
        assertEquals(8 + 16, udp.length)
    }
}
