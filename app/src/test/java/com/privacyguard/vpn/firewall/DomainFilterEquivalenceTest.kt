package com.privacyguard.vpn.firewall

import com.privacyguard.fuzz.Fuzz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The fingerprint filter (ADR 0014) must decide exactly as the string-set filter it
 * replaced. [Reference] is that filter's algorithm, copied from the 1.1 code, as the model.
 */
class DomainFilterEquivalenceTest {

    /** 1.1's DomainFilter: HashSets of names, a parent walk by substring. */
    private class Reference(domains: Iterable<String>) {
        val names = HashSet<String>()
        val wildcards = HashSet<String>()
        init {
            for (raw in domains) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) continue
                val d = line.lowercase().trimEnd('.')
                if (d.isEmpty() || d == "*") continue
                if (d.startsWith("*.")) d.substring(2).takeIf { it.isNotEmpty() }?.let { wildcards += it } else names += d
            }
        }
        fun isBlocked(domain: String, inherit: Boolean): Boolean {
            val d = domain.lowercase().trimEnd('.')
            if (d.isEmpty()) return false
            var suffix = d
            while (true) {
                if (suffix in wildcards) return true
                if (suffix in names && (suffix == d || inherit)) return true
                val dot = suffix.indexOf('.')
                if (dot < 0) return false
                suffix = suffix.substring(dot + 1)
            }
        }
    }

    private val alphabet = "abcxyz.-*AZ09 #É".toCharArray()

    private fun randomName(rnd: Random): String {
        val labels = rnd.nextInt(1, 5)
        return buildString {
            repeat(labels) { i ->
                if (i > 0) append('.')
                repeat(rnd.nextInt(0, 4)) { append(alphabet[rnd.nextInt(alphabet.size)]) }
            }
            if (rnd.nextInt(8) == 0) append('.')
            if (rnd.nextInt(10) == 0) insert(0, "*.")
        }
    }

    @Test
    fun same_decision_as_the_string_sets_for_random_lists_and_names() {
        val rnd = Random(20261105)
        repeat(200) { round ->
            val list = List(rnd.nextInt(0, 60)) { randomName(rnd) }
            val ref = Reference(list)
            val filter = DomainFilter().apply { rebuild(list) }
            assertEquals("size, round $round", (ref.names.size + ref.wildcards.size).toLong(), filter.size)
            // Probes: listed names, their subdomains and parents, and random ones.
            val probes = list.flatMap { listOf(it, "sub.$it", it.substringAfter('.'), it.uppercase(), "$it.") } +
                List(100) { randomName(rnd) }
            for (p in probes) for (inherit in listOf(true, false)) {
                assertEquals("'$p' inherit=$inherit, list $list", ref.isBlocked(p, inherit), filter.isBlocked(p, inherit))
            }
        }
    }

    @Test
    fun add_and_remove_match_a_rebuild() {
        val f = DomainFilter()
        listOf("a.example", "*.b.example", "a.example").forEach(f::addDomain)
        assertEquals(2L, f.size)
        assertTrue(f.isBlocked("x.b.example") && f.isBlocked("A.EXAMPLE."))
        assertTrue(f.removeDomain("*.b.example"))
        assertEquals(false, f.removeDomain("*.b.example"))
        assertEquals(false, f.isBlocked("x.b.example"))
        val rebuilt = DomainFilter().apply { rebuild(listOf("a.example")) }
        assertEquals(rebuilt.fingerprints().first.toList(), f.fingerprints().first.toList())
        assertEquals(rebuilt.fingerprints().second.toList(), f.fingerprints().second.toList())
    }

    @Test
    fun fingerprints_ignore_ascii_case_and_differ_for_nearby_names() {
        assertEquals(NameFingerprints.of("Ads.Example.COM"), NameFingerprints.of("ads.example.com"))
        assertEquals(NameFingerprints.of("x.ads.example.com", 2, 17), NameFingerprints.of("ads.example.com"))
        val seen = HashSet<Long>()
        // 200k close names (one character apart): no collision expected at 64 bits.
        for (i in 0 until 200_000) assertTrue(seen.add(NameFingerprints.of("host$i.example.com")))
        assertNotEquals(NameFingerprints.of("a"), NameFingerprints.of("š"))   // low byte 0x61 = 'a'
    }

    @Test
    fun a_lookup_allocates_nothing() {
        // CLAUDE.md §6.2: no per-packet allocation. 1.1's filter made a substring per parent.
        val f = DomainFilter().apply { rebuild(List(50_000) { "host$it.example.com" } + "*.wild.example") }
        val probes = arrayOf("a.b.c.d.cdn.host42.example.com", "Deep.Sub.Wild.Example.", "nothing.listed.org", "x")
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val me = Thread.currentThread().id
        repeat(20_000) { for (i in probes.indices) f.isBlocked(probes[i]) }                     // compile first
        val before = mx.getThreadAllocatedBytes(me)
        var hits = 0
        repeat(20_000) { for (i in probes.indices) if (f.isBlocked(probes[i])) hits++ }
        val allocated = mx.getThreadAllocatedBytes(me) - before
        assertEquals(40_000, hits)
        // A little slack for the measuring itself; one substring per lookup would be over 1 MB here.
        assertTrue("allocated $allocated bytes in 80,000 lookups", allocated < 4_096)
    }

    @Test
    fun lookup_never_throws_on_hostile_names() {
        val f = DomainFilter().apply { rebuild(listOf("example.com", "*.wild.example", "com")) }
        Fuzz.run("DomainFilter.isBlocked", listOf("a.example.com".toByteArray(), "...".toByteArray(), ByteArray(300) { '.'.code.toByte() })) { raw ->
            val name = String(raw, Charsets.ISO_8859_1)
            f.isBlocked(name)
            f.isBlocked(name, allowSubdomainInheritance = false)
        }
    }
}
