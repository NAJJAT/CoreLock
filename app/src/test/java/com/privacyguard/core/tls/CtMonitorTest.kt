package com.privacyguard.core.tls

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CtMonitorTest {

    private val requests = mutableListOf<String>()
    private val alerts = mutableListOf<Pair<String, List<Long>>>()
    private var answer = certs(1, 2)
    private val store = InMemoryCtBaselineStore()
    private val monitors = mutableListOf<CtMonitor>()

    private fun certs(vararg ids: Long) = ids.joinToString(",", "[", "]") { """{"id":$it,"issuer_name":"CA"}""" }

    private fun monitor() = CtMonitor(store, fetch = { url -> requests += url; answer }, runLoop = false) { d, e -> alerts += d to e.map { it.id } }
        .also { monitors += it }

    @After
    fun tearDown() = monitors.forEach { it.stop() }

    @Test
    fun nothing_is_sent_while_ct_is_off() {
        val m = monitor()
        m.addDomain("www.example.com")
        m.runDueChecks(now = 1)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun turning_ct_off_forgets_queued_domains_and_stops_requests() {
        val m = monitor()
        m.setEnabled(true)
        m.addDomain("www.example.com")
        m.setEnabled(false)
        m.runDueChecks(now = 1)
        m.setEnabled(true)
        m.runDueChecks(now = 1)
        assertTrue("domains seen before switching off must not be queried", requests.isEmpty())
    }

    @Test
    fun the_first_answer_is_a_baseline_not_an_alert() {
        val m = monitor()
        m.setEnabled(true)
        m.addDomain("shop.example.com")
        m.runDueChecks(now = 1)
        assertEquals(1, requests.size)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun only_certificates_newer_than_the_baseline_alert() {
        val m = monitor()
        m.setEnabled(true)
        m.addDomain("example.com")
        m.runDueChecks(now = 1)
        answer = certs(1, 2, 3)
        m.runDueChecks(now = 2 + CtMonitor.CHECK_INTERVAL_MS)
        assertEquals(listOf("example.com" to listOf(3L)), alerts)
    }

    @Test
    fun a_restart_does_not_alert_again_on_old_certificates() {
        monitor().apply { setEnabled(true); addDomain("example.com"); runDueChecks(now = 1) }
        // New process, same stored baseline, same certificates.
        monitor().apply { setEnabled(true); addDomain("example.com"); runDueChecks(now = 2 + CtMonitor.CHECK_INTERVAL_MS) }
        assertEquals(2, requests.size)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun a_domain_is_not_asked_again_within_six_hours() {
        val m = monitor()
        m.setEnabled(true)
        m.addDomain("example.com")
        m.runDueChecks(now = 1)
        m.runDueChecks(now = 2)
        assertEquals(1, requests.size)
    }
}
