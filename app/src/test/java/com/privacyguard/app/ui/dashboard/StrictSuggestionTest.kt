package com.privacyguard.app.ui.dashboard

import com.privacyguard.app.core.network.NetworkId
import com.privacyguard.app.core.network.NetworkIdentity
import com.privacyguard.app.core.network.NetworkSignals
import com.privacyguard.app.core.network.NetworkTrustAnalyzer
import com.privacyguard.app.data.db.DnsAnomalyEntity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** #2: the app suggests Strict, never applies it; accepting or dismissing makes it go away. */
class StrictSuggestionTest {

    private val wifi = NetworkId(NetworkIdentity.SHARED_WIFI_KEY, "Wi-Fi", isWifi = true)
    private val bad = NetworkTrustAnalyzer.score(wifi, listOf(
        DnsAnomalyEntity(timestamp = 0, packageName = NetworkSignals.NETWORK_OWNER, domain = "d",
            anomalyType = NetworkSignals.TLS_INTERCEPTION, description = "", severity = 9, networkKey = wifi.key),
        DnsAnomalyEntity(timestamp = 0, packageName = NetworkSignals.NETWORK_OWNER, domain = "d",
            anomalyType = NetworkSignals.DNS_HIJACK, description = "", severity = 8, networkKey = wifi.key),
    ))

    @Test
    fun shown_on_an_untrustworthy_wifi_at_standard() {
        assertNotNull(StrictSuggestion.of(bad, "STANDARD", emptySet()))
    }

    @Test
    fun gone_once_strict_is_accepted() {
        assertNull(StrictSuggestion.of(bad, "STRICT", emptySet()))
    }

    @Test
    fun gone_once_dismissed_even_after_leaving_strict() {
        assertNull(StrictSuggestion.of(bad, "STANDARD", setOf(wifi.key)))
    }

    @Test
    fun not_shown_on_a_trusted_network() {
        assertNull(StrictSuggestion.of(NetworkTrustAnalyzer.score(wifi, emptyList()), "MINIMAL", emptySet()))
    }
}
