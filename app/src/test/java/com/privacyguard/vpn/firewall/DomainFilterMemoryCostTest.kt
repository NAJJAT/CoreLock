package com.privacyguard.vpn.firewall

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What the blocklist filter costs on the heap, to build and per lookup, at list sizes like
 * the levels' (plan task 1.2, ADR 0014). JVM numbers: the phone's ART differs in object
 * headers and string layout, so these compare designs; they are not the phone's PSS. The
 * bounds are loose on purpose; the numbers are printed for the report.
 */
class DomainFilterMemoryCostTest {

    private fun names(count: Int, seed: Int): List<String> {
        val rnd = Random(seed)
        val tlds = listOf("com", "net", "org", "io", "de", "co.uk", "ru", "xyz")
        fun word() = buildString { repeat(rnd.nextInt(4, 13)) { append('a' + rnd.nextInt(26)) } }
        return List(count) { (if (rnd.nextInt(3) == 0) word() + "." else "") + word() + "." + tlds.random(rnd) }
    }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(4) { System.gc(); Thread.sleep(60) }
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun filter_heap_build_time_and_lookup_cost() {
        // Roughly MINIMAL (TIF mini), STANDARD (+ Pro mini), STRICT (+ Pro + OISD small).
        for ((label, count) in listOf("minimal" to 195_000, "standard" to 300_000, "strict" to 480_000)) {
            // As in the service: the names come from a list that is dropped after the build,
            // so whatever the filter keeps of them (the strings too) counts.
            val probes = names(20_000, seed = 7) + names(count, seed = count).shuffled(Random(3)).take(5_000).map { "cdn.$it" }
            val before = usedHeap()
            var buildMs = 0L
            val filter = DomainFilter().apply {
                val list = names(count, seed = count)
                val start = System.nanoTime()
                rebuild(list)
                buildMs = (System.nanoTime() - start) / 1_000_000
            }
            val retained = usedHeap() - before
            repeat(3) { for (p in probes) filter.isBlocked(p) }                    // warm up
            val t0 = System.nanoTime()
            var hits = 0
            repeat(5) { for (p in probes) if (filter.isBlocked(p)) hits++ }
            val nsPerLookup = (System.nanoTime() - t0) / (5L * probes.size)
            println("DomainFilter $label: $count names, retained ${retained / 1_048_576} MiB " +
                "(${retained / count} B/name; arrays ${"%.1f".format(filter.heapBytes.toDouble() / count)} B/name exactly), build $buildMs ms, lookup $nsPerLookup ns")
            assertTrue(hits >= 5 * 5_000)
            assertTrue(filter.size > 0)
        }
    }
}
