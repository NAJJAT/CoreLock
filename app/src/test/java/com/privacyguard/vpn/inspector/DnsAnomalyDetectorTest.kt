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
    fun cdn_names_are_not_reported_for_their_shape() {
        val d = DnsAnomalyDetector()
        val host = "35fa45fc-6c51-49ae-a008-f4490c1608d3-netseer-ipaddr-assoc.xy.fbcdn.net"
        assertTrue(d.analyze("com.instagram.android", host).isEmpty())
    }

    @Test
    fun unique_long_names_under_one_base_domain_report_once() {
        val d = DnsAnomalyDetector()
        val a = d.analyze("com.app", "b463e45b-57ac-4aa7-993d-876de14e10b6-assoc.xy.example.org")
        val b = d.analyze("com.app", "c573f56c-68bd-5bb8-aa4e-987ef25f21c7-assoc.xy.example.org")
        assertEquals(1, a.size)
        assertTrue(b.isEmpty())
    }

    @Test
    fun unattributed_lookups_use_the_unknown_key() {
        val d = DnsAnomalyDetector()
        val a = d.analyze(null, "x".repeat(60) + ".example.com")
        assertEquals("Unknown", a.single().packageName)
    }
}
