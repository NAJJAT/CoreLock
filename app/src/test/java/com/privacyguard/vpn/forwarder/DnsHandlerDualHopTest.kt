package com.privacyguard.vpn.forwarder

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.vpn.dualvpn.DualVpnConfig
import com.privacyguard.vpn.tunnel.TunInterface
import com.privacyguard.vpn.tunnel.TunWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileDescriptor
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** With dual-hop on, DoH must go through both SOCKS servers, by name. */
class DnsHandlerDualHopTest {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val connectTargets: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After
    fun tearDown() = server.close()

    @Test
    fun doh_is_sent_through_the_socks_chain() {
        // One local server plays both hops: the chain runs two SOCKS5 exchanges on one stream.
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val ins = s.getInputStream(); val outs = s.getOutputStream()
                    repeat(2) {
                        ins.readN(3)                                   // 05 01 00: no auth
                        outs.write(byteArrayOf(5, 0)); outs.flush()
                        val head = ins.readN(5)                        // 05 01 00 03 len
                        val host = String(ins.readN(head[4].toInt() and 0xFF))
                        val port = ins.readN(2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
                        connectTargets += "$host:$port"
                        outs.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); outs.flush()
                    }
                    // Closing here makes the TLS handshake fail; the route is what is under test.
                }
            }
        }
        val chain = DualVpnConfig(
            enabled = true,
            firstHopHost = "127.0.0.1", firstHopPort = server.localPort,
            secondHopHost = "hop2.example", secondHopPort = 1080,
        )
        val handler = DnsHandler(
            filterEngine = FilterEngine(),
            tunWriter = TunWriter(TunInterface(FileDescriptor())),
            dohEnabled = true,
            dohProvider = "https://cloudflare-dns.com/dns-query",
            protectSocket = { true },
            protectTcpSocket = { true },
            dualVpnConfig = { chain },
        )
        val query = DnsPacket.buildQuery("example.com", id = 7)
        handler.handle(
            IpPacket(4, 20, 0, 40, 1, false, false, 0, 64, 17, 0, "10.0.0.2", "10.0.0.1", ByteArray(0), ByteArray(40)),
            UdpPacket(40000, 53, 8 + query.size, 0, query),
        )

        val deadline = System.currentTimeMillis() + 5_000
        while (connectTargets.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("hop2.example:1080", "cloudflare-dns.com:443"), connectTargets.toList())
        handler.shutdown()
    }

    private fun InputStream.readN(n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = read(b, off, n - off); check(r >= 0); off += r }
        return b
    }
}
