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
            val synAck = synAckFor(port)
            val hello = tlsClientHello(sni)
            forwarder.handle(key, segment(seq = 1_001, ack = (synAck.sequenceNumber + 1) and 0xFFFFFFFFL, ack_ = true, window = 65_535, data = hello))
            var sawRst = false
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) { val s = nextSegment(500, forPort = port) ?: continue; if (s.flagRst) { sawRst = true; break } }
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
        val lists = com.privacyguard.vpn.firewall.DomainFilter().apply {
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

    // ── Fail closed on an unreadable ClientHello (plan task 0.2) ─────────────

    /** A forwarder as the service runs it at [failClosed] = STANDARD/STRICT, with every port read as TLS. */
    private fun failClosedForwarder(failClosed: Boolean, seenSni: MutableList<String?> = mutableListOf()) {
        forwarder.stop()
        forwarder = TcpForwarder(
            sessionTable, tunWriter, EncryptionEnforcer(), FilterEngine(),
            protectSocket = { true }, interception = TlsInterception.None,
            nameBlocked = { _, host -> DnsBlockDecision.Reason.BLOCKLIST.takeIf { host == "ads.example.com" } },
            onSni = { _, sni, _ -> seenSni += sni },
            failsClosed = { failClosed },
            tlsOnEveryPort = true,
        ).also { it.start() }
    }

    @Test
    fun a_client_hello_split_across_tls_records_is_reassembled_and_its_name_decided() {
        val seen = mutableListOf<String?>()
        failClosedForwarder(failClosed = true, seenSni = seen)
        for ((i, case) in listOf("ads.example.com" to true, "news.example.org" to false).withIndex()) {
            val (name, blocked) = case
            val msg = helloMessage(name.toByteArray())
            // The second record starts inside the SNI extension, so the first holds no name.
            val wire = records(msg, msg.size - name.length - 4)
            val (sawRst, atServer) = sendSegments(40_200 + i, listOf(wire), expectBytes = if (blocked) 1 else wire.size)
            assertEquals("RST for $name", blocked, sawRst)
            assertEquals("bytes at server for $name", if (blocked) 0 else wire.size, atServer)
        }
        assertEquals(listOf("ads.example.com", "news.example.org"), seen)
        assertEquals(1L, forwarder.sniBlocked.get())
    }

    @Test
    fun a_client_hello_split_in_records_and_segments_is_still_decided() {
        failClosedForwarder(failClosed = true)
        val msg = helloMessage("ads.example.com".toByteArray(), padding = 3_000)
        val wire = records(msg, 1, 700, 2_500)            // 4 records, the first holding 1 byte
        val segments = wire.toList().chunked(500).map { it.toByteArray() }
        val (sawRst, atServer) = sendSegments(40_210, segments, expectBytes = 1)
        assertTrue(sawRst)
        assertEquals(0, atServer)
    }

    @Test
    fun at_standard_an_unreadable_client_hello_is_reset_and_never_reaches_the_server() {
        val seen = mutableListOf<String?>()
        failClosedForwarder(failClosed = true, seenSni = seen)
        val tooLarge = helloMessage("news.example.org".toByteArray(), padding = 20_000)
        val notAHello = helloMessage("news.example.org".toByteArray()).also { it[0] = 2 }   // ServerHello type
        val truncated = helloMessage("news.example.org".toByteArray()).let { m ->
            // The handshake records end early; the next record is application data.
            records(m.copyOf(m.size - 20)) + byteArrayOf(0x17, 3, 3, 0, 20) + ByteArray(20)
        }
        val cases = listOf(
            "larger than can be reassembled" to records(tooLarge, 16_000),
            "not a ClientHello" to records(notAHello),
            "truncated" to truncated,
            "rejected name" to records(helloMessage("bad name!.example".toByteArray())),
        )
        for ((i, case) in cases.withIndex()) {
            val (what, wire) = case
            val segments = wire.toList().chunked(1_400).map { it.toByteArray() }
            val (sawRst, atServer) = sendSegments(40_220 + i, segments, expectBytes = 1)
            assertTrue("RST for $what", sawRst)
            assertEquals("bytes at server for $what", 0, atServer)
        }
        assertEquals(4L, forwarder.helloUnreadableDropped.get())
        assertEquals(0L, forwarder.sniBlocked.get())
        // Only the rejected name is reported (so its alert is raised); the others carry no name.
        assertEquals(listOf<String?>(null), seen)
    }

    /**
     * Review finding (2026-11-05): an intranet name ("wiki") or an IP literal is not a valid
     * SNI, but it is a name, not garbage. Resetting it broke intranet sites and gained
     * nothing: a hello without any SNI goes through anyway. Its alert stays.
     */
    @Test
    fun at_standard_single_label_and_ip_literal_names_go_through_with_their_alert() {
        val seen = mutableListOf<String?>()
        failClosedForwarder(failClosed = true, seenSni = seen)
        for ((i, name) in listOf("wiki", "intranet", "10.0.0.5", "[2001:db8::1]", "2001:db8::1").withIndex()) {
            val wire = records(helloMessage(name.toByteArray()))
            val (sawRst, atServer) = sendSegments(40_250 + i, listOf(wire), expectBytes = wire.size)
            assertEquals("RST for $name", false, sawRst)
            assertEquals("bytes at server for $name", wire.size, atServer)
        }
        assertEquals(0L, forwarder.helloUnreadableDropped.get())
        assertEquals(List(5) { null }, seen)        // reported as rejected (alert), with no name
    }

    /**
     * Review finding (2026-11-07): a stream on 443 that isn't TLS but happens to start with
     * 0x16 (about 1 in 256 for a random-looking protocol such as Shadowsocks) was reset at
     * Standard as an "unreadable hello". Only a TLS record header (0x16, version 3.0 to 3.4)
     * makes a stream TLS; anything else is forwarded like any other non-TLS stream. A real
     * server would not take such bytes as TLS, so no hidden server name gets through.
     */
    @Test
    fun at_standard_a_non_tls_stream_that_starts_like_a_handshake_goes_through() {
        failClosedForwarder(failClosed = true)
        val cases = listOf(
            byteArrayOf(0x16, 0x47, 0x45, 0x54, 0x20) + ByteArray(60) { 0x41 },   // 0x16, then text
            byteArrayOf(0x16, 0x02, 0x00, 0x00, 0x10) + ByteArray(40) { 7 },       // not version 3.x
        )
        for ((i, wire) in cases.withIndex()) {
            val (sawRst, atServer) = sendSegments(40_260 + i, listOf(wire), expectBytes = wire.size)
            assertEquals("RST for case $i", false, sawRst)
            assertEquals("bytes at server for case $i", wire.size, atServer)
        }
        assertEquals(0L, forwarder.helloUnreadableDropped.get())
    }

    @Test
    fun at_standard_a_complete_client_hello_without_sni_still_goes_through() {
        failClosedForwarder(failClosed = true)
        val wire = records(helloMessage(null))
        val (sawRst, atServer) = sendSegments(40_230, listOf(wire), expectBytes = wire.size)
        assertEquals(false, sawRst)
        assertEquals(wire.size, atServer)
        assertEquals(0L, forwarder.helloUnreadableDropped.get())
    }

    @Test
    fun at_minimal_an_unreadable_client_hello_is_forwarded_as_before() {
        failClosedForwarder(failClosed = false)
        val rejected = records(helloMessage("bad name!.example".toByteArray()))
        val (rst1, at1) = sendSegments(40_240, listOf(rejected), expectBytes = rejected.size)
        assertEquals(false, rst1)
        assertEquals(rejected.size, at1)
        val tooLarge = records(helloMessage("news.example.org".toByteArray(), padding = 20_000), 16_000)
        val (rst2, at2) = sendSegments(40_241, tooLarge.toList().chunked(1_400).map { it.toByteArray() }, expectBytes = tooLarge.size)
        assertEquals(false, rst2)
        assertEquals(tooLarge.size, at2)
        assertEquals(0L, forwarder.helloUnreadableDropped.get())
    }

    /**
     * One connection: [segments] are sent in order after the handshake. Returns whether the
     * device got a RST and how many bytes reached the server (read until [expectBytes] or end of stream).
     */
    private fun sendSegments(clientPort: Int, segments: List<ByteArray>, expectBytes: Int): Pair<Boolean, Int> {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val got = ByteArrayOutputStream()
        val t = thread(isDaemon = true) {
            runCatching {
                serverSocket.accept().use { s ->
                    s.soTimeout = 3_000
                    val b = ByteArray(8_192)
                    while (synchronized(got) { got.size() } < expectBytes) {
                        val n = s.getInputStream().read(b)
                        if (n < 0) break
                        synchronized(got) { got.write(b, 0, n) }
                    }
                }
            }
        }
        try {
            val key = SessionKey.of("10.0.0.2", clientPort, "127.0.0.1", serverSocket.localPort, IpPacket.PROTO_TCP)
            forwarder.handle(key, segment(seq = 1_000, ack = 0, syn = true, window = 65_535))
            val synAck = synAckFor(clientPort)
            var seq = 1_001L
            for (data in segments) {
                forwarder.handle(key, segment(seq = seq, ack = (synAck.sequenceNumber + 1) and 0xFFFFFFFFL, ack_ = true, window = 65_535, data = data))
                seq += data.size
            }
            t.join(4_000)
            var sawRst = false
            while (true) { val s = nextSegment(300, forPort = clientPort) ?: break; if (s.flagRst) sawRst = true }
            return sawRst to synchronized(got) { got.size() }
        } finally {
            serverSocket.close()
        }
    }

    /** A ClientHello handshake message (type, 3-byte length, body); [name] null sends no SNI. */
    private fun helloMessage(name: ByteArray?, padding: Int = 0): ByteArray {
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
        val pad = if (padding > 0) u16(0x0015) + u16(padding) + ByteArray(padding) else ByteArray(0)
        val sni = if (name == null) ByteArray(0)
            else u16(0) + u16(name.size + 5) + u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name
        val ext = pad + sni                  // the name last, as the split tests need
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + u16(0x1301) + byteArrayOf(1, 0) + u16(ext.size) + ext
        return byteArrayOf(1, (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
    }

    /** [message] as TLS handshake records, a new record starting at each of [cuts]. */
    private fun records(message: ByteArray, vararg cuts: Int): ByteArray {
        val bounds = listOf(0) + cuts.toList() + listOf(message.size)
        val out = ByteArrayOutputStream()
        for (i in 0 until bounds.size - 1) {
            val len = bounds[i + 1] - bounds[i]
            out.write(byteArrayOf(0x16, 3, 1, (len ushr 8).toByte(), len.toByte()))
            out.write(message, bounds[i], len)
        }
        return out.toByteArray()
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
            val synAck = synAckFor(clientPort)
            forwarder.handle(key, segment(seq = 1_001, ack = (synAck.sequenceNumber + 1) and 0xFFFFFFFFL, ack_ = true, window = 65_535, data = hello))
            // The server read ends with the ClientHello, or with EOF once the session is reset.
            t.join(3_000)
            var sawRst = false
            while (true) { val s = nextSegment(300, forPort = clientPort) ?: break; if (s.flagRst) sawRst = true }
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

    /**
     * The next segment the forwarder sent to the device, or, with [forPort], the next one
     * for that device port. The test never ACKs the FIN that ends each connection, so the
     * forwarder retransmits it (1 s, 2 s, 4 s ... up to 8 times) into the same queue. A
     * test that opens several connections in a row must skip those: on Linux CI a stale
     * FIN of an earlier connection was read as the next connection's SYN-ACK, its ACK
     * number was wrong, and the data never reached the server.
     */
    private fun nextSegment(timeoutMs: Long = 5_000, forPort: Int? = null): TcpPacket? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            val raw = injected.poll(left, TimeUnit.NANOSECONDS) ?: return null
            val ip = IpPacket.parse(raw) ?: return null
            val tcp = TcpPacket.parse(ip) ?: return null
            if (forPort == null || tcp.destinationPort == forPort) return tcp
        }
    }

    /** The SYN-ACK for the connection from device port [clientPort]. */
    private fun synAckFor(clientPort: Int): TcpPacket {
        val synAck = nextSegment(forPort = clientPort)
        assertNotNull("no SYN-ACK for port $clientPort", synAck)
        assertTrue("expected SYN-ACK for port $clientPort", synAck!!.flagSyn && synAck.flagAck)
        return synAck
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
