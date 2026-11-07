package com.privacyguard.vpn.forwarder

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.vpn.tunnel.TunInterface
import com.privacyguard.vpn.tunnel.TunWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for DnsHandler.
 *
 * TunWriter is constructed but never started — enqueue() returns false immediately
 * (running == false) without triggering any android.util.Log calls.
 * Forwarding-path Log calls in background threads are silenced by
 * testOptions { unitTests { isReturnDefaultValues = true } } in build.gradle.kts.
 */
class DnsHandlerTest {

    private lateinit var filterEngine: FilterEngine
    private lateinit var tunWriter: TunWriter
    private lateinit var handler: DnsHandler

    @Before
    fun setUp() {
        filterEngine = FilterEngine()
        tunWriter = TunWriter(TunInterface(FileDescriptor()))
        handler = DnsHandler(
            filterEngine = filterEngine,
            tunWriter = tunWriter,
            protectSocket = { _ -> false },
            protectTcpSocket = { _ -> false },
        )
    }

    // ── handle() return value ─────────────────────────────────────────────────

    @Test
    fun non_dns_udp_returns_false() {
        val udp = makeUdp(srcPort = 5000, dstPort = 443, data = ByteArray(20))
        assertFalse(handler.handle(makeIp(), udp))
    }

    @Test
    fun handle_returns_true_for_blocked_dns() {
        blockDomain("evil.example.com")
        assertTrue(handler.handle(makeIp(), dnsQueryUdp("evil.example.com")))
    }

    @Test
    fun handle_returns_true_for_forwarded_dns() {
        assertTrue(handler.handle(makeIp(), dnsQueryUdp("google.com")))
    }

    @Test
    fun empty_dns_payload_is_consumed_silently() {
        val udp = makeUdp(srcPort = 12345, dstPort = 53, data = ByteArray(0))
        assertTrue(handler.handle(makeIp(), udp))
    }

    @Test
    fun malformed_dns_increments_malformed_counter() {
        val udp = makeUdp(srcPort = 12345, dstPort = 53, data = ByteArray(5) { 0xFF.toByte() })
        val before = handler.malformedDns.get()
        handler.handle(makeIp(), udp)
        assertEquals(before + 1, handler.malformedDns.get())
    }

    @Test
    fun dns_response_is_not_handled() {
        val udp = makeUdp(srcPort = 53, dstPort = 12345, data = dnsResponseBytes("example.com"))
        assertFalse(handler.handle(makeIp(), udp))
    }

    // ── V-02: nothing sent to port 53 reaches the generic UDP forwarder ─────

    /**
     * Regression for V-02 (security test 2026-10-14): a datagram to port 53 that
     * handle() did not consume opened a plain UDP session, and later queries on
     * that session skipped the blocklist. Property: whatever an app sends to port
     * 53, handle() consumes it.
     */
    @Test
    fun every_datagram_to_port_53_is_consumed() {
        val rnd = kotlin.random.Random(20261014)
        val seeds = listOf(
            dnsResponseBytes("doubleclick.net"),                            // QR=1
            ByteArray(12).also { it[0] = 0xAB.toByte(); it[2] = 1 },         // QDCOUNT=0
            DnsPacket.buildQuery("doubleclick.net", id = 9),
        )
        repeat(5_000) { i ->
            val data = if (i % 4 == 0) rnd.nextBytes(rnd.nextInt(0, 600))
            else seeds[i % seeds.size].copyOf().also { b -> if (b.isNotEmpty() && i % 3 == 0) b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            assertTrue("datagram #$i to port 53 was handed on", handler.handle(makeIp(), makeUdp(40000, 53, data)))
        }
    }

    @Test
    fun blocked_query_after_a_priming_datagram_is_still_blocked() {
        blockDomain("doubleclick.net")
        val seen = mutableListOf<Pair<String, Boolean>>()
        handler.queryListener = DnsHandler.QueryListener { _, d, b -> seen += d to b }
        val primers = listOf(dnsResponseBytes("example.com"), ByteArray(12).also { it[2] = 1 })
        for (p in primers) {
            assertTrue(handler.handle(makeIp(), makeUdp(40000, 53, p)))
            assertTrue(handler.handle(makeIp(), makeUdp(40000, 53, DnsPacket.buildQuery("doubleclick.net", id = 7))))
        }
        assertEquals(listOf("doubleclick.net" to true, "doubleclick.net" to true), seen)
    }

    /** Regression for V-16: an allowed name that is a CNAME alias of a blocked one. */
    @Test
    fun cname_to_a_blocked_name_is_detected() {
        blockDomain("*.tracker.example")
        val cloaked = cnameResponse("metrics.news.example", "x1.tracker.example")
        assertEquals("x1.tracker.example", handler.blockedCnameTarget(DnsPacket.parse(cloaked)!!, null))
        val clean = cnameResponse("www.news.example", "edge.cdn.example")
        assertNull(handler.blockedCnameTarget(DnsPacket.parse(clean)!!, null))
    }

    /** A response for [name] with one CNAME answer pointing at [target]. */
    private fun cnameResponse(name: String, target: String): ByteArray {
        val q = DnsPacket.buildQuery(name, id = 5)
        val out = java.io.ByteArrayOutputStream()
        out.write(q.copyOfRange(0, 2)); out.write(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0))
        out.write(q.copyOfRange(12, q.size))
        val t = DnsPacket.encodeName(target)
        out.write(byteArrayOf(0xC0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, (t.size ushr 8).toByte(), t.size.toByte()))
        out.write(t)
        return out.toByteArray()
    }

    // ── HTTPS / SVCB answers (RFC 9460, plan task 1.3) ───────────────────────

    /**
     * An HTTPS (65) or SVCB (64) record's TargetName sends the client to another name's
     * servers, often with address hints so it never asks for that name's A/AAAA. A blocked
     * target gets the CNAME answer: NXDOMAIN for the whole response, recorded as blocked.
     */
    @Test
    fun https_record_pointing_at_a_blocked_name_is_answered_nxdomain() {
        blockDomain("*.tracker.example")
        val seen = mutableListOf<Pair<String, Boolean>>()
        handler.queryListener = DnsHandler.QueryListener { _, d, b -> seen += d to b }
        val cases = listOf(
            Triple(65, 1, "x1.tracker.example") to true,     // ServiceMode
            Triple(65, 0, "edge.tracker.example") to true,   // AliasMode
            Triple(64, 1, "svc.tracker.example") to true,    // SVCB
            Triple(65, 1, "edge.cdn.example") to false,      // not listed
            Triple(65, 1, "") to false,                      // "." = the asked name itself
        )
        for ((case, blocked) in cases) {
            val (type, priority, target) = case
            val query = DnsPacket.parse(serviceQuery("www.news.example", type, id = 9))!!
            val response = serviceResponse("www.news.example", type, priority, target, id = 9)
            seen.clear()
            val before = handler.svcbBlocked.get()
            handler.injectDnsResponse(makeIp(), makeUdp(40_000, 53, ByteArray(0)), query, response, null)
            assertEquals("blocked: type $type -> '$target'", blocked, seen.contains(target to true))
            assertEquals("counted: type $type -> '$target'", if (blocked) 1L else 0L, handler.svcbBlocked.get() - before)
        }
    }

    @Test
    fun a_user_allow_for_the_asked_name_wins_over_its_https_target_like_cname() {
        blockDomain("*.tracker.example")
        filterEngine.addRule(FilterRule.allowDomain("www.news.example"))
        val query = DnsPacket.parse(serviceQuery("www.news.example", 65, id = 3))!!
        val response = serviceResponse("www.news.example", 65, 1, "x1.tracker.example", id = 3)
        handler.injectDnsResponse(makeIp(), makeUdp(40_000, 53, ByteArray(0)), query, response, null)
        assertEquals(0L, handler.svcbBlocked.get())
    }

    @Test
    fun service_targets_are_read_from_https_and_svcb_records_only() {
        val p = DnsPacket.parse(serviceResponse("a.example", 65, 1, "Target.Example", id = 1))!!
        assertEquals(listOf("target.example"), p.serviceTargets)
        assertEquals(emptyList<String>(), DnsPacket.parse(serviceResponse("a.example", 65, 1, "", id = 1))!!.serviceTargets)
        assertEquals(emptyList<String>(), DnsPacket.parse(cnameResponse("a.example", "b.example"))!!.serviceTargets)
        // A target that runs past its record is not read (rdata is 3 bytes, the name needs more).
        val cut = serviceResponse("a.example", 65, 1, "long.target.example", id = 1, rdLenOverride = 3)
        assertEquals(emptyList<String>(), DnsPacket.parse(cut)?.serviceTargets ?: emptyList<String>())
    }

    // ── ECH keys (plan task 1.4, ADR 0015) ────────────────────────────────────

    /** A handler whose tunnel writes are captured; [strip] is the setting as the service passes it. */
    private fun capturingHandler(strip: () -> Boolean): Pair<DnsHandler, java.util.concurrent.LinkedBlockingQueue<ByteArray>> {
        val out = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val writer = TunWriter(object : TunWriter.PacketSink {
            override val isOpen = true
            override fun writePacket(packet: ByteArray): Boolean { out.put(packet); return true }
        }).also { it.start() }
        return DnsHandler(filterEngine = filterEngine, tunWriter = writer, protectSocket = { false },
            protectTcpSocket = { false }, stripEch = strip) to out
    }

    /** The DNS payload of the packet the handler wrote (IPv4, 20 + 8 bytes of headers). */
    private fun java.util.concurrent.LinkedBlockingQueue<ByteArray>.dnsWritten(): DnsPacket {
        val packet = poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("nothing written")
        return DnsPacket.parse(packet.copyOfRange(28, packet.size)) ?: throw AssertionError("written answer doesn't parse")
    }

    /** [serviceResponse] with an ech parameter (key 5) after the others. */
    private fun responseWithEch(name: String, id: Int): ByteArray {
        val base = serviceResponse(name, 65, 1, "", id)
        // The value happens to read as the name "abc": the unreadable test points at it.
        val ech = byteArrayOf(0, 5, 0, 8, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0, 9, 9, 9)
        val rdLenAt = base.size - 18 - 2                  // rdata: 2 + 1 (".") + 15 of params
        val rdLen = ((base[rdLenAt].toInt() and 0xFF) shl 8) or (base[rdLenAt + 1].toInt() and 0xFF)
        return (base + ech).also { it[rdLenAt] = ((rdLen + ech.size) ushr 8).toByte(); it[rdLenAt + 1] = (rdLen + ech.size).toByte() }
    }

    private fun keysIn(record: com.privacyguard.core.packet.DnsRecord): List<Int> {
        val r = record.rdata
        var p = 3                                          // priority, "." target
        val keys = mutableListOf<Int>()
        while (p + 4 <= r.size) {
            keys += ((r[p].toInt() and 0xFF) shl 8) or (r[p + 1].toInt() and 0xFF)
            p += 4 + (((r[p + 2].toInt() and 0xFF) shl 8) or (r[p + 3].toInt() and 0xFF))
        }
        return keys
    }

    /**
     * Review finding (2026-11-05): the asked name is allowed, only its HTTPS record points
     * at a blocked name. NXDOMAIN said the allowed name doesn't exist (and some resolvers
     * cache that for its A/AAAA lookups too); an empty answer only removes the record.
     */
    @Test
    fun a_blocked_https_target_gets_an_empty_answer_not_nxdomain() {
        blockDomain("*.tracker.example")
        val (h, out) = capturingHandler { false }
        val query = DnsPacket.parse(serviceQuery("www.news.example", 65, id = 24))!!
        h.injectDnsResponse(makeIp(), makeUdp(40_000, 53, ByteArray(0)), query,
            serviceResponse("www.news.example", 65, 1, "x1.tracker.example", id = 24), null)
        val written = out.dnsWritten()
        assertEquals("NOERROR", 0, written.responseCode)
        assertEquals(0, written.answers.size)
        assertEquals("www.news.example", written.queryName)
        assertEquals(1L, h.svcbBlocked.get())
    }

    @Test
    fun with_the_setting_on_the_ech_key_is_removed_before_the_app_sees_the_answer() {
        val (h, out) = capturingHandler { true }
        val query = DnsPacket.parse(serviceQuery("www.news.example", 65, id = 21))!!
        assertTrue(h.injectDnsResponse(makeIp(), makeUdp(40_000, 53, ByteArray(0)), query, responseWithEch("www.news.example", 21), null))
        val written = out.dnsWritten()
        assertEquals(listOf(1, 4), keysIn(written.answers.single()))       // alpn and ipv4hint stay
        assertEquals(1L, h.echKeysRemoved.get())
    }

    @Test
    fun with_the_setting_off_the_answer_passes_unchanged() {
        val (h, out) = capturingHandler { false }
        val query = DnsPacket.parse(serviceQuery("www.news.example", 65, id = 22))!!
        h.injectDnsResponse(makeIp(), makeUdp(40_000, 53, ByteArray(0)), query, responseWithEch("www.news.example", 22), null)
        assertEquals(listOf(1, 4, 5), keysIn(out.dnsWritten().answers.single()))
        assertEquals(0L, h.echKeysRemoved.get())
    }

    @Test
    fun an_answer_that_cannot_be_rewritten_safely_becomes_an_empty_answer() {
        val (h, out) = capturingHandler { true }
        val query = DnsPacket.parse(serviceQuery("www.news.example", 65, id = 23))!!
        // An extra record whose name points into the ech value: valid DNS (it reads "abc"),
        // but once the key is removed that pointer would point at other bytes.
        val base = responseWithEch("www.news.example", 23)
        val echValueAt = base.size - 8
        val extra = byteArrayOf(0xC0.toByte(), echValueAt.toByte(), 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4)
        val response = (base + extra).also { it[11] = 1 }                // ARCOUNT = 1
        assertEquals("abc", DnsPacket.parse(response)!!.additional.single().name)
        h.injectDnsResponse(makeIp(), makeUdp(40_000, 53, ByteArray(0)), query, response, null)
        val written = out.dnsWritten()
        assertEquals(0, written.answers.size)
        assertEquals(0, written.responseCode)
        assertEquals(1L, h.echUnreadable.get())
    }

    /** A query for [name] of [type]. */
    private fun serviceQuery(name: String, type: Int, id: Int): ByteArray {
        val n = DnsPacket.encodeName(name)
        return byteArrayOf((id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) + n +
            byteArrayOf((type ushr 8).toByte(), type.toByte(), 0, 1)
    }

    /**
     * A response with one [type] record: SvcPriority, TargetName ([target] "" = "."), then
     * alpn=h2 and an ipv4hint, as real HTTPS records carry.
     */
    private fun serviceResponse(name: String, type: Int, priority: Int, target: String, id: Int, rdLenOverride: Int? = null): ByteArray {
        val q = serviceQuery(name, type, id)
        val t = if (target.isEmpty()) byteArrayOf(0) else DnsPacket.encodeName(target)
        val params = byteArrayOf(0, 1, 0, 3, 2, 'h'.code.toByte(), '2'.code.toByte(), 0, 4, 0, 4, 93.toByte(), 184.toByte(), 216.toByte(), 34)
        val rdata = byteArrayOf((priority ushr 8).toByte(), priority.toByte()) + t + (if (priority == 0) ByteArray(0) else params)
        val rdLen = rdLenOverride ?: rdata.size
        val header = q.copyOfRange(0, 2) + byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
        val answer = byteArrayOf(0xC0.toByte(), 12, (type ushr 8).toByte(), type.toByte(), 0, 1, 0, 0, 0, 60,
            (rdLen ushr 8).toByte(), rdLen.toByte()) + rdata.copyOf(minOf(rdLen, rdata.size))
        return header + q.copyOfRange(12, q.size) + answer
    }

    // ── queryListener — blocked path ──────────────────────────────────────────

    @Test
    fun blocked_domain_fires_queryListener_wasBlocked_true() {
        blockDomain("tracker.ad")
        var captured: Boolean? = null
        handler.queryListener = DnsHandler.QueryListener { _, _, wasBlocked -> captured = wasBlocked }
        handler.handle(makeIp(), dnsQueryUdp("tracker.ad"), ownerPackage = "com.test.app")
        assertNotNull(captured)
        assertEquals(true, captured)
    }

    @Test
    fun blocked_domain_passes_correct_domain_to_listener() {
        blockDomain("ads.example.com")
        var domain: String? = null
        handler.queryListener = DnsHandler.QueryListener { _, d, _ -> domain = d }
        handler.handle(makeIp(), dnsQueryUdp("ads.example.com"))
        assertEquals("ads.example.com", domain)
    }

    @Test
    fun blocked_domain_passes_ownerPackage_to_listener() {
        blockDomain("blocked.test")
        var pkg: String? = "UNSET"
        handler.queryListener = DnsHandler.QueryListener { p, _, _ -> pkg = p }
        handler.handle(makeIp(), dnsQueryUdp("blocked.test"), ownerPackage = "com.my.app")
        assertEquals("com.my.app", pkg)
    }

    @Test
    fun null_ownerPackage_forwarded_to_listener_as_null() {
        blockDomain("blocked.test")
        var pkg: String? = "UNSET"
        handler.queryListener = DnsHandler.QueryListener { p, _, _ -> pkg = p }
        handler.handle(makeIp(), dnsQueryUdp("blocked.test"), ownerPackage = null)
        assertNull(pkg)
    }

    @Test
    fun blocked_domain_increments_blockedCount() {
        blockDomain("block.me")
        val before = handler.blockedCount.get()
        handler.handle(makeIp(), dnsQueryUdp("block.me"))
        assertEquals(before + 1, handler.blockedCount.get())
    }

    @Test
    fun blocked_domain_does_not_increment_forwardCount() {
        blockDomain("block.me")
        val before = handler.forwardCount.get()
        handler.handle(makeIp(), dnsQueryUdp("block.me"))
        assertEquals(before, handler.forwardCount.get())
    }

    // ── queryListener — forwarded path ────────────────────────────────────────

    @Test
    fun forwarded_domain_fires_queryListener_wasBlocked_false() {
        val latch = CountDownLatch(1)
        var wasBlocked: Boolean? = null
        handler.queryListener = DnsHandler.QueryListener { _, _, blocked ->
            wasBlocked = blocked
            latch.countDown()
        }
        handler.handle(makeIp(), dnsQueryUdp("safe.example.com"))
        latch.await(2, TimeUnit.SECONDS)
        assertEquals(false, wasBlocked)
    }

    @Test
    fun forwarded_domain_increments_forwardCount() {
        val latch = CountDownLatch(1)
        handler.queryListener = DnsHandler.QueryListener { _, _, _ -> latch.countDown() }
        handler.handle(makeIp(), dnsQueryUdp("safe.example.com"))
        latch.await(2, TimeUnit.SECONDS)
        assertEquals(1L, handler.forwardCount.get())
    }

    @Test
    fun malformed_payload_queryListener_not_called() {
        var called = false
        handler.queryListener = DnsHandler.QueryListener { _, _, _ -> called = true }
        val udp = makeUdp(srcPort = 12345, dstPort = 53, data = ByteArray(5) { 0xFF.toByte() })
        handler.handle(makeIp(), udp)
        assertFalse(called)
    }

    @Test
    fun dns_response_packet_queryListener_not_called() {
        var called = false
        handler.queryListener = DnsHandler.QueryListener { _, _, _ -> called = true }
        val udp = makeUdp(srcPort = 53, dstPort = 12345, data = dnsResponseBytes("example.com"))
        handler.handle(makeIp(), udp)
        assertFalse(called)
    }

    // ── stats() ───────────────────────────────────────────────────────────────

    @Test
    fun stats_blocked_count_reflects_blocked_queries() {
        blockDomain("a.com")
        blockDomain("b.com")
        handler.handle(makeIp(), dnsQueryUdp("a.com"))
        handler.handle(makeIp(), dnsQueryUdp("b.com"))
        handler.handle(makeIp(), dnsQueryUdp("c.com"))
        assertEquals(2L, handler.stats().blocked)
    }

    @Test
    fun stats_forwarded_count_reflects_forwarded_queries() {
        blockDomain("block.com")
        handler.handle(makeIp(), dnsQueryUdp("allowed1.com"))
        handler.handle(makeIp(), dnsQueryUdp("allowed2.com"))
        handler.handle(makeIp(), dnsQueryUdp("block.com"))
        assertEquals(2L, handler.stats().forwarded)
    }

    @Test
    fun multiple_blocked_domains_each_fire_listener() {
        blockDomain("x.com"); blockDomain("y.com"); blockDomain("z.com")
        val captured = mutableListOf<String>()
        handler.queryListener = DnsHandler.QueryListener { _, d, _ -> captured += d }
        handler.handle(makeIp(), dnsQueryUdp("x.com"))
        handler.handle(makeIp(), dnsQueryUdp("y.com"))
        handler.handle(makeIp(), dnsQueryUdp("z.com"))
        assertEquals(listOf("x.com", "y.com", "z.com"), captured)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun blockDomain(domain: String) {
        filterEngine.addRule(FilterRule(
            id = "test-block-$domain",
            label = "Block $domain",
            action = FilterRule.Action.DENY,
            source = FilterRule.Source.USER,
            matchDomain = domain,
        ))
    }

    private fun makeIp(src: String = "10.0.0.1", dst: String = "8.8.8.8") = IpPacket(
        version = 4, headerLength = 20, dscp = 0, totalLength = 40,
        identification = 1, flagDf = false, flagMf = false, fragmentOffset = 0,
        ttl = 64, protocol = 17, headerChecksum = 0,
        sourceIp = src, destinationIp = dst,
        options = ByteArray(0), rawPacket = ByteArray(40),
    )

    private fun makeUdp(srcPort: Int, dstPort: Int, data: ByteArray) = UdpPacket(
        sourcePort = srcPort, destinationPort = dstPort,
        length = 8 + data.size, checksum = 0, data = data,
    )

    private fun dnsQueryUdp(domain: String): UdpPacket {
        val payload = DnsPacket.buildQuery(domain, id = 42)
        return makeUdp(srcPort = 12345, dstPort = 53, data = payload)
    }

    private fun dnsResponseBytes(domain: String): ByteArray {
        val query = DnsPacket.buildQuery(domain, id = 77)
        return query.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }
    }
}
