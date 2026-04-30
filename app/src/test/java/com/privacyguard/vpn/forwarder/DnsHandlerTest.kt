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
