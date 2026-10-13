package com.privacyguard.app.core.network

import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsAnomalyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTrustAnalyzerTest {

    private val home = NetworkId("wi-fi: home", "Wi-Fi “home”", isWifi = true)
    private val cafe = "wi-fi: cafe"

    private fun conn(status: String, network: String? = home.key) =
        ConnectionEntity(appUid = 1, encryptionStatus = status, networkKey = network)

    private fun anomaly(type: String, network: String? = home.key) =
        DnsAnomalyEntity(timestamp = 0, packageName = "p", domain = "d", anomalyType = type,
            description = "", severity = 5, networkKey = network)

    @Test
    fun unknown_encryption_is_not_cleartext() {
        val s = NetworkTrustAnalyzer.score(home, List(20) { conn("UNKNOWN") }, emptyList())
        assertEquals(0, s.cleartextCount)
        assertEquals(100, s.score)
        assertEquals("Trusted", s.level)
    }

    @Test
    fun only_confirmed_cleartext_counts() {
        val s = NetworkTrustAnalyzer.score(home, listOf(conn("CLEARTEXT"), conn("TLS_1_3"), conn("UNKNOWN")), emptyList())
        assertEquals(1, s.cleartextCount)
        assertEquals(97, s.score)
    }

    @Test
    fun traffic_from_other_networks_and_unstamped_rows_is_ignored() {
        val elsewhere = List(30) { conn("CLEARTEXT", cafe) } + List(30) { conn("CLEARTEXT", null) }
        val s = NetworkTrustAnalyzer.score(home, elsewhere, List(10) { anomaly("DGA_BEACON", cafe) } + anomaly("DGA_BEACON", null))
        assertEquals(0, s.cleartextCount)
        assertEquals(0, s.dnsAnomalyCount)
        assertEquals(100, s.score)
        assertFalse(s.shouldRecommendStrict)
    }

    @Test
    fun bad_signals_on_this_network_reduce_trust_and_suggest_strict_on_wifi() {
        val conns = List(10) { conn("CLEARTEXT") } + List(5) { conn("WEAK_TLS") }
        val s = NetworkTrustAnalyzer.score(home, conns, List(6) { anomaly("DGA_BEACON") })
        assertEquals("Reduced", s.level)
        assertTrue(s.shouldRecommendStrict)
        assertEquals(home.key, s.networkKey)
    }

    @Test
    fun cellular_never_suggests_strict() {
        val cell = NetworkId("cellular network", "Mobile", isWifi = false)
        val conns = List(10) { conn("CLEARTEXT", cell.key) } + List(5) { conn("WEAK_TLS", cell.key) }
        val s = NetworkTrustAnalyzer.score(cell, conns, List(6) { anomaly("DGA_BEACON", cell.key) })
        assertEquals("Reduced", s.level)
        assertFalse(s.shouldRecommendStrict)
    }
}
