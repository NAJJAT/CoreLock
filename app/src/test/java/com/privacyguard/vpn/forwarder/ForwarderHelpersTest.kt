package com.privacyguard.vpn.forwarder

import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.Ipv6Packet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ForwarderHelpersTest {

    // ── DnsHandler.isIpLiteral ───────────────────────────────────────────────

    @Test
    fun ip_literals_are_accepted() {
        listOf("1.1.1.1", "149.112.112.112", "0.0.0.0", "2606:4700:4700::1111", "::1", "fe80::", "1:2:3:4:5:6:7:8")
            .forEach { assertTrue(it, DnsHandler.isIpLiteral(it)) }
    }

    @Test
    fun partial_or_named_values_are_rejected() {
        listOf("", "1.", "1.1.1", "1.1.1.1.", "256.1.1.1", "01.1.1.1", "dns.google", "1::2::3", "1:2:3:4:5:6:7:8:9", "12345::")
            .forEach { assertFalse(it, DnsHandler.isIpLiteral(it)) }
    }

    // ── DnsPacket SERVFAIL ───────────────────────────────────────────────────

    @Test
    fun servfail_echoes_the_question_with_rcode_2() {
        val query = DnsPacket.parse(DnsPacket.buildQuery("example.com", id = 0x1234))!!
        val response = DnsPacket.parse(DnsPacket.buildServfailResponse(query))
        assertNotNull(response)
        assertTrue(response!!.isResponse)
        assertEquals(0x1234, response.id)
        assertEquals(2, response.responseCode)
        assertEquals("example.com", response.queryName)
    }

    // ── UDP reply fragmentation ──────────────────────────────────────────────

    @Test
    fun oversized_ipv4_packet_is_fragmented_and_reassembles() {
        val payload = ByteArray(4_000) { it.toByte() }
        val packet = IpPacket.build(IpPacket.PROTO_UDP, "1.2.3.4", "10.0.0.2", payload)
        val fragments = UdpForwarder.fragmentIpv4(packet, 1500, id = 42)

        assertTrue(fragments.size > 1)
        val reassembled = ByteArrayOutputStream()
        fragments.forEachIndexed { i, frag ->
            assertTrue(frag.size <= 1500)
            val ip = IpPacket.parse(frag)!!
            assertEquals(42, ip.identification)
            assertEquals(i < fragments.lastIndex, ip.flagMf)
            assertEquals(reassembled.size(), ip.fragmentOffset)   // already in bytes
            reassembled.write(frag, 20, frag.size - 20)
        }
        assertArrayEquals(packet.copyOfRange(20, packet.size), reassembled.toByteArray())
    }

    @Test
    fun small_packets_are_not_fragmented() {
        val packet = IpPacket.build(IpPacket.PROTO_UDP, "1.2.3.4", "10.0.0.2", ByteArray(100))
        assertEquals(listOf(packet), UdpForwarder.fragmentIpv4(packet, 1500, id = 1))
    }

    @Test
    fun oversized_ipv6_packet_gets_fragment_headers() {
        val packet = Ipv6Packet.buildUdp("2001:db8::1", 443, "fd00:1:fd00:1::2", 5000, ByteArray(3_000) { it.toByte() })
        val fragments = UdpForwarder.fragmentIpv6(packet, 1500, id = 7)

        assertTrue(fragments.size > 1)
        val reassembled = ByteArrayOutputStream()
        fragments.forEachIndexed { i, frag ->
            assertTrue(frag.size <= 1500)
            assertEquals(44, frag[6].toInt() and 0xFF)
            assertEquals(Ipv6Packet.PROTO_UDP, frag[40].toInt() and 0xFF)
            val offWord = ((frag[42].toInt() and 0xFF) shl 8) or (frag[43].toInt() and 0xFF)
            assertEquals(reassembled.size(), offWord and 0xFFF8)
            assertEquals(i < fragments.lastIndex, offWord and 1 == 1)
            reassembled.write(frag, 48, frag.size - 48)
        }
        assertArrayEquals(packet.copyOfRange(40, packet.size), reassembled.toByteArray())
    }
}
