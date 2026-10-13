package com.privacyguard.app.core.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTrafficMeterTest {

    @Test
    fun first_tick_has_no_total_rate_yet() {
        val m = LiveTrafficMeter()
        assertEquals(0L, m.tick(1_000, mapOf("a" to 500L), 0).total)
    }

    @Test
    fun rates_are_bytes_per_second() {
        val m = LiveTrafficMeter()
        m.tick(1_000, mapOf("a" to 1_000L), 0)
        val r = m.tick(3_000, mapOf("a" to 5_000L), 0)       // +4000 bytes in 2 s
        assertEquals(2_000L, r.total)
        assertEquals(2_000L, r.perSession["a"])
    }

    @Test
    fun a_session_closing_between_ticks_still_counts() {
        val m = LiveTrafficMeter()
        m.tick(1_000, mapOf("a" to 1_000L, "b" to 100L), 0)
        // "a" closed with 3000 bytes total: it moves into the closed total.
        val r = m.tick(2_000, mapOf("b" to 600L), closedBytesTotal = 3_000L)
        assertEquals(2_500L, r.total)                         // (3000 + 600) - (1000 + 100)
        assertEquals(500L, r.perSession["b"])
    }

    @Test
    fun a_new_session_counts_all_its_bytes() {
        val m = LiveTrafficMeter()
        m.tick(1_000, emptyMap(), 0)
        val r = m.tick(2_000, mapOf("c" to 800L), 0)
        assertEquals(800L, r.perSession["c"])
        assertEquals(800L, r.total)
    }
}
