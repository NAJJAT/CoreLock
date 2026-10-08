package com.privacyguard.vpn.forwarder

import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.utils.Checksum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpSegmentsTest {

    @Test
    fun sequenceArithmeticWrapsAt2To32() {
        val nearEnd = 0xFFFF_FF00L
        val wrapped = TcpSegments.add(nearEnd, 0x200)
        assertEquals(0x100L, wrapped)
        // After the wrap the new value is still "ahead" of the old one.
        assertEquals(0x200L, TcpSegments.diff(wrapped, nearEnd))
        assertEquals(-0x200L, TcpSegments.diff(nearEnd, wrapped))
    }

    @Test
    fun windowShiftIsReadFromSynOptions() {
        // MSS 1460, SACK permitted, timestamps, NOP, window scale 8 — Android's SYN.
        val options = byteArrayOf(
            2, 4, 0x05, 0xB4.toByte(),
            4, 2,
            8, 10, 0, 0, 0, 1, 0, 0, 0, 0,
            1,
            3, 3, 8,
        )
        assertEquals(8, TcpSegments.windowShift(options))
        assertEquals(-1, TcpSegments.windowShift(byteArrayOf(2, 4, 0x05, 0xB4.toByte())))
        assertEquals(-1, TcpSegments.windowShift(byteArrayOf(3, 9)))   // malformed length
        assertEquals(14, TcpSegments.windowShift(byteArrayOf(3, 3, 30))) // capped per RFC 7323
    }

    @Test
    fun synAckCarriesMssAndWindowScale() {
        val pkt = TcpSegments.build(
            srcIp = "93.184.216.34", srcPort = 443,
            dstIp = "10.0.0.2", dstPort = 40000,
            seq = 1000, ack = 0xFFFF_FFFFL,
            flags = TcpSegments.SYN or TcpSegments.ACK,
            windowScale = true,
        )
        val tcp = TcpPacket.parse(IpPacket.parse(pkt)!!)!!
        assertTrue(tcp.flagSyn && tcp.flagAck)
        assertEquals(0xFFFF_FFFFL, tcp.acknowledgmentNumber)
        assertEquals(28, tcp.headerLength)
        assertEquals(TcpSegments.MSS, ((tcp.options[2].toInt() and 0xFF) shl 8) or (tcp.options[3].toInt() and 0xFF))
        assertEquals(0, TcpSegments.windowShift(tcp.options))
        assertTrue(Checksum.verifyTcp(pkt))
        assertTrue(Checksum.verifyIpv4Header(pkt))
    }

    @Test
    fun dataSegmentCopiesOnlyTheRequestedSlice() {
        val data = ByteArray(3000) { it.toByte() }
        val pkt = TcpSegments.build(
            srcIp = "1.2.3.4", srcPort = 443, dstIp = "10.0.0.2", dstPort = 5555,
            seq = 7, ack = 9, flags = TcpSegments.ACK, window = 1234,
            data = data, dataOffset = 1460, dataLength = 1460,
        )
        val tcp = TcpPacket.parse(IpPacket.parse(pkt)!!)!!
        assertEquals(1460, tcp.data.size)
        assertEquals(data[1460], tcp.data[0])
        assertEquals(1234, tcp.windowSize)
        assertTrue(Checksum.verifyTcp(pkt))
    }

    @Test
    fun icmpUnreachableQuotesTheRefusedDatagram() {
        val payload = ByteArray(40) { 7 }
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val raw = ByteArray(total)
        raw[0] = 0x45; raw[3] = total.toByte(); raw[8] = 64; raw[9] = 17
        byteArrayOf(10, 0, 0, 2).copyInto(raw, 12)
        byteArrayOf(142.toByte(), 250.toByte(), 1, 1).copyInto(raw, 16)
        raw[20] = 0x9C.toByte(); raw[21] = 0x40; raw[22] = 0x01; raw[23] = 0xBB.toByte()
        raw[25] = udpLen.toByte()
        payload.copyInto(raw, 28)
        Checksum.setIpv4HeaderChecksum(raw)
        Checksum.setUdpChecksum(raw)
        val ip = IpPacket.parse(raw)!!
        assertNotNull(UdpPacket.parse(ip))

        val reply = IcmpUnreachable.replyTo(ip)!!
        val parsed = IpPacket.parse(reply)!!
        assertEquals(IpPacket.PROTO_ICMP, parsed.protocol)
        assertEquals("142.250.1.1", parsed.sourceIp)
        assertEquals("10.0.0.2", parsed.destinationIp)
        assertEquals(3, reply[20].toInt())                 // destination unreachable
        assertEquals(3, reply[21].toInt())                 // port unreachable
        assertEquals(20 + 8 + 28, reply.size)              // header + quoted IP header + 8 bytes
        assertEquals(0, Checksum.checksum(reply, 20, reply.size - 20))
        assertTrue(Checksum.verifyIpv4Header(reply))
    }
}
