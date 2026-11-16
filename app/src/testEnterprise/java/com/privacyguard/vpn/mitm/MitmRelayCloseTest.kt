package com.privacyguard.vpn.mitm

import com.privacyguard.app.core.stats.StatsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.Closeable
import java.io.IOException

/** Closing a relay closes both sides even when the first close fails, and counts the error. */
class MitmRelayCloseTest {

    private class Side(private val fails: Boolean) : Closeable {
        var closed = false
        override fun close() { closed = true; if (fails) throw IOException("reset") }
    }

    @Before fun setUp() = StatsManager.reset()

    @Test fun second_side_is_closed_when_the_first_close_fails() {
        val a = Side(fails = true); val b = Side(fails = false)
        MitmEngine.closeBoth(a, b)
        assertTrue(a.closed); assertTrue("second side left open", b.closed)
        StatsManager.publishNow()
        assertEquals(1L, StatsManager.snapshot.value.inspectionCloseErrors)
    }

    @Test fun clean_close_counts_nothing() {
        val a = Side(false); val b = Side(false)
        MitmEngine.closeBoth(a, b)
        assertTrue(a.closed && b.closed)
        StatsManager.publishNow()
        assertEquals(0L, StatsManager.snapshot.value.inspectionCloseErrors)
    }
}
