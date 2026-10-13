package com.privacyguard.app.core.network

import com.privacyguard.app.data.db.DnsAnomalyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Network trust counts signs about the network only; one test per signal. */
class NetworkTrustAnalyzerTest {

    private val home = NetworkId("wi-fi: home", "Wi-Fi “home”", isWifi = true, openWifi = false)
    private val cafe = "wi-fi: cafe"

    private fun finding(type: String, network: String? = home.key) =
        DnsAnomalyEntity(timestamp = 0, packageName = NetworkSignals.NETWORK_OWNER, domain = "d",
            anomalyType = type, description = "", severity = 6, networkKey = network)

    @Test
    fun a_clean_network_is_trusted() {
        val s = NetworkTrustAnalyzer.score(home, emptyList())
        assertEquals(100, s.score)
        assertEquals("Trusted", s.level)
        assertFalse(s.shouldRecommendStrict)
    }

    @Test
    fun open_wifi_costs_30() {
        val s = NetworkTrustAnalyzer.score(home.copy(openWifi = true), emptyList())
        assertEquals(70, s.score)
        assertTrue(s.openWifi)
    }

    @Test
    fun unknown_wifi_security_costs_nothing() {
        assertEquals(100, NetworkTrustAnalyzer.score(home.copy(openWifi = null), emptyList()).score)
    }

    @Test
    fun captive_portal_costs_15() {
        assertEquals(85, NetworkTrustAnalyzer.score(home.copy(captivePortal = true), emptyList()).score)
    }

    @Test
    fun tampered_dns_answers_cost_10_each_up_to_30() {
        assertEquals(80, NetworkTrustAnalyzer.score(home, List(2) { finding(NetworkSignals.DNS_TAMPERED) }).score)
        assertEquals(70, NetworkTrustAnalyzer.score(home, List(9) { finding(NetworkSignals.DNS_TAMPERED) }).score)
    }

    @Test
    fun dns_hijacking_costs_30() {
        val s = NetworkTrustAnalyzer.score(home, listOf(finding(NetworkSignals.DNS_HIJACK)))
        assertEquals(70, s.score)
        assertTrue(s.dnsHijacked)
    }

    @Test
    fun tls_interception_costs_40() {
        val s = NetworkTrustAnalyzer.score(home, listOf(finding(NetworkSignals.TLS_INTERCEPTION)))
        assertEquals(60, s.score)
        assertTrue(s.tlsIntercepted)
    }

    @Test
    fun app_behaviour_findings_do_not_count() {
        // What apps do (random names, bursts, long names) is not about the network.
        val appFindings = listOf("DGA_BEACON", "HIGH_ENTROPY", "HIGH_FREQUENCY", "LONG_LABEL", "EXCESSIVE_SUBDOMAINS")
            .map { finding(it) }
        assertEquals(100, NetworkTrustAnalyzer.score(home, appFindings).score)
    }

    @Test
    fun findings_from_other_networks_or_none_are_ignored() {
        val elsewhere = listOf(finding(NetworkSignals.TLS_INTERCEPTION, cafe), finding(NetworkSignals.DNS_HIJACK, null))
        assertEquals(100, NetworkTrustAnalyzer.score(home, elsewhere).score)
    }

    @Test
    fun an_untrustworthy_wifi_suggests_strict_but_mobile_never_does() {
        val bad = listOf(finding(NetworkSignals.TLS_INTERCEPTION), finding(NetworkSignals.DNS_HIJACK))
        val wifi = NetworkTrustAnalyzer.score(home, bad)
        assertEquals("Reduced", wifi.level)
        assertTrue(wifi.shouldRecommendStrict)
        val cell = NetworkId("cellular network", "Mobile", isWifi = false)
        val mobile = NetworkTrustAnalyzer.score(cell, bad.map { it.copy(networkKey = cell.key) })
        assertEquals("Reduced", mobile.level)
        assertFalse(mobile.shouldRecommendStrict)
    }
}
