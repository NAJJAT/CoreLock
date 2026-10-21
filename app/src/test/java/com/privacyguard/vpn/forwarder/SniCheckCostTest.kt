package com.privacyguard.vpn.forwarder

import com.privacyguard.app.adprivacy.AdSdkPolicy
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.vpn.firewall.DomainFilter
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What the SNI check adds to each new TLS connection: one NameBlockPolicy call,
 * the same work as a DNS lookup's decision. Runs once per connection (on the
 * ClientHello), never per packet.
 *
 * Sized like the default lists (~220k names) plus a malware list and 50 user rules,
 * as DomainFilter looks names up on the device.
 * The bound is loose on purpose, to catch a regression (say, a linear scan) without
 * failing on a busy CI machine; the measured numbers are printed.
 */
class SniCheckCostTest {

    @Test
    fun sni_check_costs_microseconds_per_connection() {
        val rnd = Random(42)
        val tlds = listOf("com", "net", "org", "io", "de", "co.uk")
        fun word() = buildString { repeat(rnd.nextInt(4, 11)) { append('a' + rnd.nextInt(26)) } }
        val listed = List(220_000) { "${word()}.${tlds.random(rnd)}" }

        val lists = DomainFilter().apply { rebuild(listed) }
        val security = DomainFilter().apply { rebuild(List(20_000) { "${word()}.${tlds.random(rnd)}" }) }
        val engine = FilterEngine().apply {
            setRules(List(50) {
                val host = "${word()}.${tlds.random(rnd)}"
                if (it % 2 == 0) FilterRule.allowDomain(host) else FilterRule.blockDomain("*.$host", source = FilterRule.Source.USER)
            })
        }
        val policy = NameBlockPolicy(engine, lists, security, AdSdkPolicy(enabled = { true }))

        // Realistic SNIs: 3–5 labels, a quarter under a listed name, some mixed case.
        val snis = List(20_000) {
            val base = if (rnd.nextInt(4) == 0) listed.random(rnd) else "${word()}.${tlds.random(rnd)}"
            val name = List(rnd.nextInt(1, 4)) { word() }.joinToString(".") + "." + base
            if (rnd.nextInt(10) == 0) name.uppercase() else name
        }

        repeat(5) { for (s in snis) policy.reasonFor(s, "com.example.app") }   // warm up the JIT

        val rounds = 7
        val perCallNs = LongArray(rounds) {
            val start = System.nanoTime()
            var blocked = 0
            for (s in snis) if (policy.reasonFor(s, "com.example.app") != null) blocked++
            assertTrue("some names are blocked", blocked > 0)
            (System.nanoTime() - start) / snis.size
        }.sorted()
        val median = perCallNs[rounds / 2]
        println("SNI check: median ${median} ns per connection (min ${perCallNs.first()}, max ${perCallNs.last()}), " +
            "${listed.size} listed names, 50 user rules")
        assertTrue("SNI check took $median ns per connection", median < 50_000)
    }
}
