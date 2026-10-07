package com.privacyguard.app.core.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.concurrent.thread

class StatsManagerTest {

    @Test
    fun recordIpv6BlockedTracksPacketsAndBytes() {
        StatsManager.reset()

        StatsManager.recordIpv6Blocked(128)
        StatsManager.recordIpv6Blocked(256)
        StatsManager.publishNow()

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

    @Test
    fun concurrentRecordsAreNotLost() {
        StatsManager.reset()

        (1..4).map { thread { repeat(10_000) { StatsManager.recordPacket(1) } } }.forEach { it.join() }
        StatsManager.publishNow()

        assertEquals(40_000L, StatsManager.snapshot.value.totalPackets)
        assertEquals(40_000L, StatsManager.snapshot.value.dataSavedBytes)
    }

    @Test
    fun publishesAtMostOncePerIntervalThenCatchesUp() {
        // Let any publish scheduled by an earlier test fire first.
        Thread.sleep(StatsManager.PUBLISH_INTERVAL_MS + 200)
        StatsManager.reset()

        repeat(1_000) { StatsManager.recordPacket(10) }
        // Not published per packet...
        assertEquals(0L, StatsManager.snapshot.value.totalPackets)

        // ...but the trailing publish delivers the final numbers.
        Thread.sleep(StatsManager.PUBLISH_INTERVAL_MS + 500)
        assertEquals(1_000L, StatsManager.snapshot.value.totalPackets)
    }
}
