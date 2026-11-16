package com.privacyguard.vpn.inspector

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * An app on the phone can look up as many random names as it likes. The detector runs on
 * the packet thread for every lookup, so nothing it keeps may grow with the number of
 * distinct names (§3.2 adversary 2 and 6).
 */
class DnsAnomalyMemoryBoundTest {

    private fun randomName(r: Random): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val label = (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
        return "$label.com"
    }

    @Test
    fun many_distinct_random_names_keep_the_tracked_state_bounded() {
        val d = DnsAnomalyDetector()
        val r = Random(20261116)
        // Each name is a new base domain with a random-looking label, so each one is a new
        // de-duplication key, all inside the 24 h cooldown.
        repeat(12_000) { d.analyze("com.flooder", randomName(r)) }
        assertTrue("reports tracked: ${d.trackedReportCount()}",
            d.trackedReportCount() <= DnsAnomalyDetector.MAX_TRACKED_REPORTS)
        assertTrue("names held: ${d.heldNameCount()}", d.heldNameCount() <= 100)
    }
}
