package com.privacyguard.vpn.forwarder

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.session.SessionKey
import com.privacyguard.core.session.SessionTable
import com.privacyguard.vpn.inspector.EncryptionEnforcer
import com.privacyguard.vpn.interception.TlsInterception
import com.privacyguard.vpn.tunnel.TunWriter
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Drives TcpForwarder from the device side against a real loopback server. The
 * test plays the app's TCP stack: it sends segments in and reads the segments the
 * forwarder injects from a captured TUN sink.
 */
class TcpForwarderTest {

    private val injected = LinkedBlockingQueue<ByteArray>()
    private lateinit var tunWriter: TunWriter
    private lateinit var sessionTable: SessionTable
    private lateinit var forwarder: TcpForwarder
    private var server: ServerSocket? = null

    private val sink = object : TunWriter.PacketSink {
        override val isOpen = true
        override fun writePacket(packet: ByteArray): Boolean {
            injected.put(packet)
            return true
        }
    }

    @Before
    fun setUp() {
        tunWriter = TunWriter(sink).also { it.start() }
        sessionTable = SessionTable()
        forwarder = TcpForwarder(
            sessionTable, tunWriter, EncryptionEnforcer(), FilterEngine(),
            protectSocket = { true },
            interception = TlsInterception.None,
        ).also { it.start() }
    }

    @After
    fun tearDown() {
        forwarder.stop()
        tunWriter.stop()
        sessionTable.clear()
        server?.close()
    }

    @Test
    fun download_survives_a_lost_segment_and_respects_the_window() {
        val response = Random(7).nextBytes(200_000)
        val request = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        val received = ByteArrayOutputStream()
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { server = it }
        val serverGotRequest = ByteArrayOutputStream()
        thread(isDaemon = true) {
            serverSocket.accept().use { s ->
                val buf = ByteArray(request.size)
                var n = 0
                while (n < buf.size) n += s.getInputStream().read(buf, n, buf.size - n)
                serverGotRequest.write(buf)
                s.getOutputStream().write(response)
                s.getOutputStream().flush()
            }
        }

        val window = 8_192
        val key = SessionKey.of("10.0.0.2", 40_000, "127.0.0.1", serverSocket.localPort, IpPacket.PROTO_TCP)
        val deviceIsn = 1_000L
        forwarder.handle(key, segment(seq = deviceIsn, ack = 0, syn = true, window = window))
        val synAck = nextSegment()!!
        assertTrue("expected SYN-ACK", synAck.flagSyn && synAck.flagAck)
        assertEquals(deviceIsn + 1, synAck.acknowledgmentNumber)

        var expected = (synAck.sequenceNumber + 1) and 0xFFFFFFFFL
        forwarder.handle(key, segment(seq = deviceIsn + 1, ack = expected, ack_ = true, window = window, data = request))

        // The device "loses" the first data segment it sees; afterwards it behaves
        // like a normal receiver: buffers out-of-order data, ACKs cumulatively.
        var dropped = false
        var sawFin = false
        val outOfOrder = HashMap<Long, ByteArray>()
        val deadline = System.currentTimeMillis() + 20_000
        while (!sawFin && System.currentTimeMillis() < deadline) {
            val seg = nextSegment(timeoutMs = 5_000) ?: break
            if (seg.flagRst) break
            if (seg.data.isNotEmpty()) {
                // Never more in flight than the window we advertised.
                val end = (seg.sequenceNumber + seg.data.size - expected) and 0xFFFFFFFFL
                assertTrue("segment beyond advertised window", end <= window || end > 0x80000000L)
                if (!dropped) { dropped = true; continue }
                if (seg.sequenceNumber == expected) {
                    received.write(seg.data)
                    expected = (expected + seg.data.size) and 0xFFFFFFFFL
                    while (true) {
                        val next = outOfOrder.remove(expected) ?: break
                        received.write(next)
                        expected = (expected + next.size) and 0xFFFFFFFFL
                    }
                } else if (((seg.sequenceNumber - expected) and 0xFFFFFFFFL) < 0x80000000L) {
                    outOfOrder[seg.sequenceNumber] = seg.data
                }
            }
            if (seg.flagFin && seg.sequenceNumber + seg.data.size == expected) {
                expected = (expected + 1) and 0xFFFFFFFFL
                sawFin = true
            }
            forwarder.handle(key, segment(seq = deviceIsn + 1 + request.size, ack = expected, ack_ = true, window = window))
        }

        assertArrayEquals(request, serverGotRequest.toByteArray())
        assertEquals(response.size, received.size())
        assertArrayEquals(response, received.toByteArray())
        assertTrue("server FIN not passed on", sawFin)
        assertTrue("nothing was retransmitted", forwarder.retransmittedSegments.get() > 0)
    }

    @Test
    fun syn_to_a_closed_port_is_refused_with_an_acceptable_rst() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }   // now closed
        val key = SessionKey.of("10.0.0.2", 40_001, "127.0.0.1", port, IpPacket.PROTO_TCP)
        forwarder.handle(key, segment(seq = 5_000, ack = 0, syn = true, window = 65_535))
        val rst = nextSegment()
        assertNotNull(rst)
        assertTrue(rst!!.flagRst)
        // In SYN-SENT the app only accepts a RST that ACKs its SYN.
        assertTrue(rst.flagAck)
        assertEquals(5_001L, rst.acknowledgmentNumber)
    }

    @Test
    fun data_for_an_unknown_connection_is_reset() {
        val key = SessionKey.of("10.0.0.2", 40_002, "127.0.0.1", 9, IpPacket.PROTO_TCP)
        forwarder.handle(key, segment(seq = 77, ack = 1234, ack_ = true, window = 65_535, data = byteArrayOf(1, 2, 3)))
        val rst = nextSegment()
        assertNotNull(rst)
        assertTrue(rst!!.flagRst)
        assertEquals(1234L, rst.sequenceNumber)
    }

    /** Regression for V-07: a TLS connection to a blocked name is reset, even without DNS. */
    @Test
    fun client_hello_with_a_blocked_sni_is_reset_and_never_reaches_the_server() {
        forwarder.stop()
        val seenSni = mutableListOf<String?>()
        forwarder = TcpForwarder(
            sessionTable, tunWriter, EncryptionEnforcer(), FilterEngine(),
            protectSocket = { true }, interception = TlsInterception.None,
            nameBlocked = { _, host -> DnsBlockDecision.Reason.BLOCKLIST.takeIf { host == "ads.example.com" || host.endsWith(".ads.example.com") } },
            onSni = { _, sni, _ -> seenSni += sni },
        ).also { it.start() }

        for ((port, sni, blocked) in listOf(Triple(40_010, "cdn.ads.example.com", true), Triple(40_011, "news.example.org", false))) {
            val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val got = ByteArrayOutputStream()
            val t = thread(isDaemon = true) {
                runCatching { serverSocket.accept().use { s -> s.soTimeout = 2_000; runCatching { val b = ByteArray(4096); val n = s.getInputStream().read(b); if (n > 0) got.write(b, 0, n) } } }
            }
            val key = SessionKey.of("10.0.0.2", port, "127.0.0.1", serverSocket.localPort, IpPacket.PROTO_TCP)
            forwarder.handle(key, segment(seq = 1_000, ack = 0, syn = true, window = 65_535))
            val synAck = nextSegment()!!
            val hello = tlsClientHello(sni)
            forwarder.handle(key, segment(seq = 1_001, ack = (synAck.sequenceNumber + 1) and 0xFFFFFFFFL, ack_ = true, window = 65_535, data = hello))
            var sawRst = false
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) { val s = nextSegment(500) ?: continue; if (s.flagRst) { sawRst = true; break } }
            t.join(3_000); serverSocket.close()
            assertEquals("RST for $sni", blocked, sawRst)
            assertEquals("bytes at server for $sni", if (blocked) 0 else hello.size, got.size())
        }
        assertEquals(listOf("cdn.ads.example.com", "news.example.org"), seenSni)
        assertEquals(1L, forwarder.sniBlocked.get())
    }

    /**
     * Layer 3 with the real shared decision (NameBlockPolicy), as the service wires it:
     * every case gets exactly the answer DNS would give, and a block is reported as SNI.
     */
    @Test
    fun sni_uses_the_dns_decision_and_is_reported_as_an_sni_block() {
        forwarder.stop()
        val engine = FilterEngine().apply { addRule(com.privacyguard.core.filter.FilterRule.allowDomain("allowed-ads.example.net")) }
        val lists = com.privacyguard.vpn.firewall.DomainFilter(useNativeBloom = false).apply {
            rebuild(listOf("ads.example.com", "allowed-ads.example.net", "graph.facebook.com"))
        }
        val policy = NameBlockPolicy(engine, domainFilter = lists,
            adSdkPolicy = com.privacyguard.app.adprivacy.AdSdkPolicy(enabled = { true }))
        // Recorded, not asserted, in the callback: the forwarder contains listener failures.
        val reported = mutableListOf<Pair<com.privacyguard.core.session.BlockSource?, DnsBlockDecision.Reason>>()
        forwarder = TcpForwarder(
            sessionTable, tunWriter, EncryptionEnforcer(), engine,
            protectSocket = { true }, interception = TlsInterception.None,
            nameBlocked = { pkg, host -> policy.reasonFor(host, pkg) },
            onSniBlocked = { session, reason -> reported += session.blockSource to reason },
        ).also { it.start() }

        val cases = listOf(
            "ads.example.com" to true,            // on the level list
            "cdn.ads.example.com" to true,        // subdomain of a listed parent
            "ADS.Example.COM." to true,           // case and trailing dot normalized
            "allowed-ads.example.net" to false,   // user Allow beats the list
            "graph.facebook.com" to false,        // AdSdkPolicy.neverBlock beats the list
            "news.example.org" to false,          // not listed
            null to false,                        // no SNI (IP-only TLS, ECH): unchanged
        )
        for ((i, case) in cases.withIndex()) {
            val (sni, blocked) = case
            val hello = tlsClientHello(sni)
            val (sawRst, bytesAtServer) = sendClientHello(40_100 + i, hello)
            assertEquals("RST for $sni", blocked, sawRst)
            assertEquals("bytes at server for $sni", if (blocked) 0 else hello.size, bytesAtServer)
        }
        assertEquals(3L, forwarder.sniBlocked.get())
        assertEquals(List(3) { com.privacyguard.core.session.BlockSource.SNI to DnsBlockDecision.Reason.BLOCKLIST }, reported)
    }

    /** One TLS connection to a loopback server: whether the device got a RST, and what reached the server. */
    private fun sendClientHello(clientPort: Int, hello: ByteArray): Pair<Boolean, Int> {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val got = ByteArrayOutputStream()
        val t = thread(isDaemon = true) {
            runCatching { serverSocket.accept().use { s -> s.soTimeout = 2_000; runCatching { val b = ByteArray(4096); val n = s.getInputStream().read(b); if (n > 0) got.write(b, 0, n) } } }
        }
        try {
            val key = SessionKey.of("10.0.0.2", clientPort, "127.0.0.1", serverSocket.localPort, IpPacket.PROTO_TCP)
            forwarder.handle(key, segment(seq = 1_000, ack = 0, syn = true, window = 65_535))
            val synAck = nextSegment()!!
            forwarder.handle(key, segment(seq = 1_001, ack = (synAck.sequenceNumber + 1) and 0xFFFFFFFFL, ack_ = true, window = 65_535, data = hello))
            // The server read ends with the ClientHello, or with EOF once the session is reset.
            t.join(3_000)
            var sawRst = false
            while (true) { val s = nextSegment(300) ?: break; if (s.flagRst) sawRst = true }
            return sawRst to got.size()
        } finally {
            serverSocket.close()
        }
    }

    /** A minimal TLS 1.3 ClientHello; [sni] null sends none (as IP-only TLS or ECH's outer name might). */
    private fun tlsClientHello(sni: String?): ByteArray {
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        val name = sni?.toByteArray()
        val ext = if (name == null) ByteArray(0)
            else u16(0) + u16(name.size + 5) + u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + u16(0x1301) + byteArrayOf(1, 0) + u16(ext.size) + ext
        val hs = byteArrayOf(1, 0) + u16(body.size) + body
        return byteArrayOf(0x16, 3, 1) + u16(hs.size) + hs
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun nextSegment(timeoutMs: Long = 5_000): TcpPacket? {
        val raw = injected.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        val ip = IpPacket.parse(raw) ?: return null
        return TcpPacket.parse(ip)
    }

    @Suppress("LocalVariableName")
    private fun segment(
        seq: Long, ack: Long, window: Int,
        syn: Boolean = false, ack_ : Boolean = false, fin: Boolean = false,
        data: ByteArray = ByteArray(0),
    ) = TcpPacket(
        sourcePort = 0, destinationPort = 0,   // the key carries the ports
        sequenceNumber = seq and 0xFFFFFFFFL,
        acknowledgmentNumber = ack and 0xFFFFFFFFL,
        headerLength = 20,
        flagNs = false, flagCwr = false, flagEce = false, flagUrg = false,
        flagAck = ack_, flagPsh = data.isNotEmpty(), flagRst = false, flagSyn = syn, flagFin = fin,
        windowSize = window,
        checksum = 0, urgentPointer = 0,
        options = ByteArray(0),
        data = data,
    )
}
