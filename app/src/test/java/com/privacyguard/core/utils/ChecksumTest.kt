package com.privacyguard.core.utils

import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.packet.UdpPacket
import org.junit.Assert.*
import org.junit.Test

class ChecksumTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeTcpPacket(
        srcIp: String = "10.0.0.1",
        dstIp: String = "93.184.216.34",
        srcPort: Int = 54321,
        dstPort: Int = 443,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val tcpBytes = TcpPacket.buildSyn(srcPort, dstPort, 1L).toBytes()
        val data = tcpBytes + payload
        val ip = IpPacket.build(IpPacket.PROTO_TCP, srcIp, dstIp, data)
        Checksum.setTcpChecksum(ip, 0)
        return ip
    }

    private fun makeUdpPacket(
        srcIp: String = "10.0.0.1",
        dstIp: String = "8.8.8.8",
        srcPort: Int = 54321,
        dstPort: Int = 53,
        payload: ByteArray = ByteArray(4),
    ): ByteArray {
        val udpBytes = UdpPacket.build(srcPort, dstPort, payload).toBytes()
        val ip = IpPacket.build(IpPacket.PROTO_UDP, srcIp, dstIp, udpBytes)
        Checksum.setUdpChecksum(ip, 0)
        return ip
    }

    // ── IPv4 header checksum ──────────────────────────────────────────────────

    @Test
    fun setIpv4HeaderChecksum_produces_valid_checksum() {
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "10.0.0.1", "8.8.8.8", ByteArray(20))
        assertTrue(Checksum.verifyIpv4Header(raw, 0))
    }

    @Test
    fun verifyIpv4Header_fails_after_single_byte_flip() {
        val raw = IpPacket.build(IpPacket.PROTO_UDP, "192.168.1.1", "8.8.8.8", ByteArray(8))
        // Flip a bit in the destination IP
        raw[19] = (raw[19].toInt() xor 0x01).toByte()
        assertFalse(Checksum.verifyIpv4Header(raw, 0))
    }

    @Test
    fun setIpv4HeaderChecksum_is_idempotent() {
        val raw = IpPacket.build(IpPacket.PROTO_TCP, "1.2.3.4", "5.6.7.8", ByteArray(20))
        // Calling set twice should give the same result
        Checksum.setIpv4HeaderChecksum(raw, 0)
        val cs1 = ByteUtils.readUInt16(raw, 10)
        Checksum.setIpv4HeaderChecksum(raw, 0)
        val cs2 = ByteUtils.readUInt16(raw, 10)
        assertEquals(cs1, cs2)
        assertTrue(Checksum.verifyIpv4Header(raw, 0))
    }

    // ── TCP checksum ──────────────────────────────────────────────────────────

    @Test
    fun setTcpChecksum_produces_valid_checksum() {
        val raw = makeTcpPacket()
        assertTrue(Checksum.verifyTcp(raw, 0))
    }

    @Test
    fun verifyTcp_fails_after_data_byte_flip() {
        val raw = makeTcpPacket(payload = byteArrayOf(0x47, 0x45, 0x54))   // "GET"
        assertTrue(Checksum.verifyTcp(raw, 0))
        // Flip a byte in the TCP payload
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0xFF).toByte()
        assertFalse(Checksum.verifyTcp(raw, 0))
    }

    @Test
    fun tcp_checksum_changes_when_source_ip_changes() {
        val raw1 = makeTcpPacket(srcIp = "10.0.0.1")
        val raw2 = makeTcpPacket(srcIp = "10.0.0.2")
        val cs1  = ByteUtils.readUInt16(raw1, 20 + 16)   // TCP checksum at IP_hdr+16
        val cs2  = ByteUtils.readUInt16(raw2, 20 + 16)
        assertNotEquals(cs1, cs2)
    }

    @Test
    fun tcp_checksum_over_non_trivial_payload_verifies() {
        val payload = ByteArray(100) { (it * 7 + 13).toByte() }
        val raw = makeTcpPacket(payload = payload)
        assertTrue(Checksum.verifyTcp(raw, 0))
    }

    // ── UDP checksum ──────────────────────────────────────────────────────────

    @Test
    fun setUdpChecksum_produces_valid_checksum() {
        val raw = makeUdpPacket()
        assertTrue(Checksum.verifyUdp(raw, 0))
    }

    @Test
    fun verifyUdp_returns_true_when_checksum_is_zero_disabled() {
        val raw = makeUdpPacket()
        // Zero out the UDP checksum field → checksum disabled per RFC 768
        val ihl = (raw[0].toInt() and 0x0F) * 4
        raw[ihl + 6] = 0
        raw[ihl + 7] = 0
        assertTrue(Checksum.verifyUdp(raw, 0))
    }

    @Test
    fun verifyUdp_fails_after_payload_byte_flip() {
        val payload = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val raw = makeUdpPacket(payload = payload)
        assertTrue(Checksum.verifyUdp(raw, 0))
        // Flip last payload byte
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        assertFalse(Checksum.verifyUdp(raw, 0))
    }

    @Test
    fun udp_checksum_over_dns_like_payload_verifies() {
        // 12-byte payload resembling a minimal DNS query header
        val dns = byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val raw = makeUdpPacket(dstPort = 53, payload = dns)
        assertTrue(Checksum.verifyUdp(raw, 0))
    }

    // ── core compute ─────────────────────────────────────────────────────────

    @Test
    fun compute_of_known_value_is_correct() {
        // RFC 1071 example: header bytes 45 00 00 3c 1c 46 40 00 40 06 00 00 ac 10 0a 63 ac 10 0a 0c
        // With checksum zeros → sum should fold to known value
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46.toByte(), 0x40, 0x00,
            0x40, 0x06, 0x00, 0x00,
            0xac.toByte(), 0x10, 0x0a, 0x63,
            0xac.toByte(), 0x10, 0x0a, 0x0c
        )
        val cs = Checksum.checksum(header)
        // After writing cs back and re-summing, should verify
        val withCs = header.copyOf()
        ByteUtils.writeUInt16(withCs, 10, cs)
        assertEquals(0xFFFF, Checksum.compute(withCs))
    }

    @Test
    fun checksum_of_all_zeros_is_ffff() {
        val zeros = ByteArray(20)
        assertEquals(0xFFFF, Checksum.checksum(zeros))
    }

    @Test
    fun checksum_of_odd_length_buffer_handles_padding() {
        val buf = byteArrayOf(0x01, 0x02, 0x03)   // odd length
        val cs = Checksum.checksum(buf)
        assertTrue(cs in 0..0xFFFF)
        // verify: sum(buf + checksum) should give 0xFFFF
        val withCs = buf + byteArrayOf((cs ushr 8).toByte(), cs.toByte())
        // The compute over odd buffer + 2 checksum bytes should yield 0xFFFF
        // (Strictly: compute over buf alone + checksum word should complement to 0)
        val verify = Checksum.compute(buf) + cs
        val folded = ((verify and 0xFFFF) + (verify ushr 16)) and 0xFFFF
        assertEquals(0xFFFF, folded)
    }
}
