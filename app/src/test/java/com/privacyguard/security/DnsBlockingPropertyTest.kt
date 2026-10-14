package com.privacyguard.security

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.vpn.firewall.DomainFilter
import com.privacyguard.vpn.forwarder.DnsHandler
import com.privacyguard.vpn.tunnel.TunInterface
import com.privacyguard.vpn.tunnel.TunWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import kotlin.random.Random

/**
 * Security property tests (thousands of random cases, fixed seeds so failures
 * reproduce). Invariant: with the VPN on, a blocklisted name never reaches the
 * upstream resolver through DnsHandler.
 */
class DnsBlockingPropertyTest {

    private val blocked = listOf("doubleclick.net", "ads.tracker-example.com", "evil-c2.ru", "t.co.example.org")
    private val rnd = Random(20261013)

    private fun handler(rules: List<FilterRule> = emptyList(), security: List<String> = emptyList()): Pair<DnsHandler, MutableList<Pair<String, Boolean>>> {
        val engine = FilterEngine().apply { rules.forEach(::addRule) }
        val seen = mutableListOf<Pair<String, Boolean>>()
        val h = DnsHandler(
            filterEngine = engine,
            tunWriter = TunWriter(TunInterface(FileDescriptor())),
            protectSocket = { false }, protectTcpSocket = { false },
            domainFilter = DomainFilter(useNativeBloom = false).apply { rebuild(blocked) },
            securityFilter = DomainFilter(useNativeBloom = false).apply { rebuild(security) },
        )
        h.queryListener = DnsHandler.QueryListener { _, d, b -> seen += d to b }
        return h to seen
    }

    private val ip = IpPacket(4, 20, 0, 40, 1, false, false, 0, 64, 17, 0, "10.0.0.2", "10.0.0.1", ByteArray(0), ByteArray(40))
    private fun udp(payload: ByteArray, dstPort: Int = 53) = UdpPacket(40000, dstPort, 8 + payload.size, 0, payload)

    private fun randomLabel(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789-"
        return (1..rnd.nextInt(1, 20)).map { chars[rnd.nextInt(chars.length)] }.joinToString("").trim('-').ifEmpty { "a" }
    }

    private fun randomCase(s: String) = s.map { if (rnd.nextBoolean()) it.uppercaseChar() else it }.joinToString("")

    /** Raw query with any QTYPE (A, AAAA, HTTPS=65, ANY=255, TXT=16…). */
    private fun query(name: String, qtype: Int, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        name.trimEnd('.').split('.').forEach { l -> out.write(l.length); out.write(l.toByteArray(Charsets.US_ASCII)) }
        out.write(0)
        out.write(byteArrayOf((qtype ushr 8).toByte(), qtype.toByte(), 0, 1))
        return out.toByteArray()
    }

    @Test
    fun a_blocked_name_or_subdomain_never_resolves_whatever_its_case_type_or_spelling() {
        val (h, seen) = handler()
        val types = listOf(1, 28, 65, 255, 16, 5, 33)
        repeat(5_000) { i ->
            val base = blocked[rnd.nextInt(blocked.size)]
            val sub = (0 until rnd.nextInt(0, 4)).joinToString("") { randomLabel() + "." }
            val name = randomCase(sub + base) + if (rnd.nextBoolean()) "." else ""
            seen.clear()
            h.handle(ip, udp(query(name, types[rnd.nextInt(types.size)], i and 0xFFFF)))
            assertEquals("case $i: $name", listOf(true), seen.map { it.second })
        }
        h.shutdown()
    }

    @Test
    fun dns_to_any_address_on_port_53_is_filtered_not_only_the_tunnel_resolver() {
        val (h, seen) = handler()
        repeat(500) { i ->
            val dst = "${rnd.nextInt(1, 224)}.${rnd.nextInt(256)}.${rnd.nextInt(256)}.${rnd.nextInt(1, 255)}"
            seen.clear()
            h.handle(ip.copy(destinationIp = dst), udp(query("doubleclick.net", 1, i)))
            assertEquals(listOf(true), seen.map { it.second })
        }
        h.shutdown()
    }

    @Test
    fun an_allow_rule_never_opens_a_malware_name_without_the_warning() {
        repeat(1_000) { i ->
            val name = "${randomLabel()}.evil-c2.ru"
            val (h, seen) = handler(rules = listOf(FilterRule.allowDomain(name), FilterRule.allowDomain("*.evil-c2.ru")), security = listOf("evil-c2.ru"))
            h.handle(ip, udp(query(name, 1, i)))
            assertEquals(listOf(true), seen.map { it.second })
            h.shutdown()
        }
    }

    /** Regression for V-16: only the first question was checked; QDCOUNT ≠ 1 is now dropped. */
    @Test
    fun a_query_with_a_second_question_is_dropped() {
        val (h, seen) = handler()
        val q = query("allowed.example", 1, 7)
        // Same header, QDCOUNT=2, then a second question for a blocked name.
        q[5] = 2
        val second = ByteArrayOutputStream().apply {
            "doubleclick.net".split('.').forEach { write(it.length); write(it.toByteArray()) }
            write(0); write(byteArrayOf(0, 1, 0, 1))
        }.toByteArray()
        assertTrue(h.handle(ip, udp(q + second)))
        // Consumed and never forwarded: nothing is looked up for either question.
        assertEquals(emptyList<Pair<String, Boolean>>(), seen)
        h.shutdown()
    }

    @Test
    fun random_bytes_on_port_53_never_crash_the_handler() {
        val (h, _) = handler()
        repeat(20_000) {
            val bytes = ByteArray(rnd.nextInt(0, 600)).also { rnd.nextBytes(it) }
            runCatching { h.handle(ip, udp(bytes)) }.onFailure { e -> throw AssertionError("crash on ${bytes.size} bytes", e) }
        }
        h.shutdown()
        assertTrue(true)
    }
}
