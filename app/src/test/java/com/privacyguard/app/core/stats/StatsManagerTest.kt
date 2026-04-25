package com.privacyguard.app.core.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsManagerTest {

    @Test
    fun recordIpv6BlockedTracksPacketsAndBytes() {
        StatsManager.reset()

        StatsManager.recordIpv6Blocked(128)
        StatsManager.recordIpv6Blocked(256)

        val snapshot = StatsManager.snapshot.value
        assertEquals(2L, snapshot.totalPackets)
        assertEquals(2L, snapshot.ipv6PacketsBlocked)
        assertEquals(384L, snapshot.ipv6BytesBlocked)
    }

    @Test
    fun resetClearsIpv6LeakCounters() {
        StatsManager.recordIpv6Blocked(128)

        StatsManager.reset()

        val snapshot = StatsManager.snapshot.value
        assertEquals(0L, snapshot.totalPackets)
        assertEquals(0L, snapshot.ipv6PacketsBlocked)
        assertEquals(0L, snapshot.ipv6BytesBlocked)
    }
}
