package com.privacyguard.fuzz

import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.Ipv6Packet
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.tls.ClientHelloParser
import org.junit.Test

/**
 * Every parser here reads bytes that another app on the device (or a remote
 * server) controls. Malformed input must be rejected with null, never an
 * exception that could kill the VPN's packet loop.
 */
class PacketParserFuzzTest {

    private fun u8(v: Int) = byteArrayOf(v.toByte())
    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u24(v: Int) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private val tcpHeader = u16(51234) + u16(443) + byteArrayOf(0, 0, 0, 1) + byteArrayOf(0, 0, 0, 0) +
        u8(0x50) + u8(0x02) + u16(65535) + u16(0) + u16(0)

    private val dnsQuery = DnsPacket.buildQuery("ads.example.com", 0x1234)

    // Response with a compression pointer back to the question name.
    private val dnsResponse = u16(0x1234) + u16(0x8180) + u16(1) + u16(1) + u16(0) + u16(0) +
        DnsPacket.encodeName("example.com") + u16(1) + u16(1) +
        u16(0xC00C) + u16(1) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(4) + byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

    // CNAME whose name is a pointer to itself.
    private val dnsPointerLoop = u16(1) + u16(0x8180) + u16(0) + u16(1) + u16(0) + u16(0) +
        u16(0xC00C) + u16(5) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(2) + u16(0xC00C)

    private val udpDns = u16(40000) + u16(53) + u16(8 + dnsQuery.size) + u16(0) + dnsQuery

    private val clientHello: ByteArray = run {
        val name = "example.com".toByteArray()
        val sniEntry = u8(0) + u16(name.size) + name
        val sni = u16(0x0000) + u16(sniEntry.size + 2) + u16(sniEntry.size) + sniEntry
        val alpnList = u8(2) + "h2".toByteArray()
        val alpn = u16(0x0010) + u16(alpnList.size + 2) + u16(alpnList.size) + alpnList
        val versions = u16(0x002b) + u16(3) + u8(2) + u16(0x0304)
        val extensions = sni + alpn + versions
        val body = u16(0x0303) + ByteArray(32) + u8(0) + u16(4) + u16(0x1301) + u16(0xC02B) +
            u8(1) + u8(0) + u16(extensions.size) + extensions
        val handshake = u8(0x01) + u24(body.size) + body
        u8(0x16) + u16(0x0301) + u16(handshake.size) + handshake
    }

    private val ipv4Seeds = listOf(
        IpPacket.build(IpPacket.PROTO_TCP, "10.0.0.2", "93.184.216.34", tcpHeader),
        IpPacket.build(IpPacket.PROTO_TCP, "10.0.0.2", "93.184.216.34", tcpHeader + clientHello),
        IpPacket.build(IpPacket.PROTO_UDP, "10.0.0.2", "10.0.0.1", udpDns),
    )

    private val ipv6Seed: ByteArray =
        byteArrayOf(0x60, 0, 0, 0) + u16(udpDns.size) + u8(17) + u8(64) +
            ByteArray(15) + u8(1) + ByteArray(15) + u8(2) + udpDns

    @Test fun ipv4AndTransport() = Fuzz.run("IpPacket/Tcp/Udp", ipv4Seeds) { raw ->
        val ip = IpPacket.parse(raw) ?: return@run
        when (ip.protocol) {
            IpPacket.PROTO_TCP -> TcpPacket.parse(ip)
            IpPacket.PROTO_UDP -> UdpPacket.parse(ip)?.let { DnsPacket.parse(it.data) }
        }
    }

    @Test fun ipv6() = Fuzz.run("Ipv6Packet", listOf(ipv6Seed)) { Ipv6Packet.parse(it) }

    @Test fun dns() = Fuzz.run("DnsPacket", listOf(dnsQuery, dnsResponse, dnsPointerLoop)) { raw ->
        // DnsHandler answers a blocked query by echoing its question back.
        DnsPacket.parse(raw)?.let { DnsPacket.buildBlockedResponse(it) }
    }

    @Test fun tlsClientHello() = Fuzz.run("ClientHelloParser", listOf(clientHello)) {
        ClientHelloParser.parse(it)
    }
}
