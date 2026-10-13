package com.privacyguard.vpn.inspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsAnomalyDetectorTest {

    private fun burstTypes(d: DnsAnomalyDetector, pkg: String, names: List<String>) =
        names.flatMap { d.analyze(pkg, it) }.map { it.type }

    @Test
    fun a_and_aaaa_for_twenty_names_is_not_a_burst() {
        val d = DnsAnomalyDetector()
        // 20 names, each asked twice (A + AAAA): 40 packets, 20 lookups.
        val names = (1..20).flatMap { listOf("host$it.example.com", "host$it.example.com") }
        assertTrue(DnsAnomalyDetector.AnomalyType.HIGH_FREQUENCY !in burstTypes(d, "com.browser", names))
    }

    @Test
    fun many_distinct_names_is_a_medium_burst() {
        val d = DnsAnomalyDetector()
        val all = (1..40).flatMap { d.analyze("com.app", "n$it.example.com") }
        val burst = all.filter { it.type == DnsAnomalyDetector.AnomalyType.HIGH_FREQUENCY }
        assertTrue(burst.isNotEmpty())
        assertTrue(burst.all { it.severity < 7 })   // not "High"
    }

    @Test
    fun own_service_hosts_are_never_reported() {
        val d = DnsAnomalyDetector()
        val all = (1..50).flatMap { d.analyze(null, "crt.sh") + d.analyze(null, "a$it.raw.githubusercontent.com") }
        assertTrue(all.isEmpty())
    }

    @Test
    fun one_report_per_app_and_host() {
        val d = DnsAnomalyDetector()
        val long = "a".repeat(30) + ".b.c.d.e.f.example.com"   // long and many labels
        val first = d.analyze("com.app", long)
        Thread.sleep(1_100)                                  // past the same-lookup window
        val second = d.analyze("com.app", long)
        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
    }

    @Test
    fun unattributed_lookups_use_the_unknown_key() {
        val d = DnsAnomalyDetector()
        val a = d.analyze(null, "x".repeat(60) + ".example.com")
        assertEquals("Unknown", a.single().packageName)
    }
}
