package com.privacyguard.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine tracks a bounded number of connections (each one holds a socket). Without
 * a bound, one app sending to many ports used up the process's file descriptors and
 * every connection on the phone failed. At a limit, idle UDP sessions make room first;
 * if none can, the new flow is refused (fail closed) and counted.
 */
class SessionTableLimitsTest {

    private val refused = mutableListOf<SessionKey>()
    private val evicted = mutableListOf<SessionKey>()

    private fun table(tcp: Int = 4, udp: Int = 4, perApp: Int = 100) = SessionTable(
        limits = SessionTable.Limits(maxTcp = tcp, maxUdp = udp, maxPerApp = perApp),
    ).apply {
        limitListener = object : SessionTable.LimitListener {
            override fun onRefused(key: SessionKey) { refused += key }
            override fun onEvicted(key: SessionKey) { evicted += key }
        }
    }

    private fun tcp(port: Int) = SessionKey.of("10.0.0.2", port, "203.0.113.9", 443, SessionKey.PROTO_TCP)
    private fun udp(port: Int) = SessionKey.of("10.0.0.2", port, "203.0.113.9", 5000, SessionKey.PROTO_UDP)

    /** Makes [s] look idle since [ms] ago. */
    private fun Session.idleFor(ms: Long) = apply { lastActivityAt = System.currentTimeMillis() - ms }

    @Test
    fun tcp_over_the_limit_is_refused_and_counted() {
        val t = table(tcp = 3)
        repeat(3) { assertNotNull(t.tryCreate(tcp(1000 + it), uid = 10_001)) }
        assertNull(t.tryCreate(tcp(2000), uid = 10_001))
        assertEquals(listOf(tcp(2000)), refused)
        assertEquals(3, t.size)
    }

    @Test
    fun tcp_sessions_are_never_evicted_for_room() {
        val t = table(tcp = 2)
        repeat(2) { t.tryCreate(tcp(1000 + it), uid = 10_001)!!.idleFor(60_000) }
        assertNull(t.tryCreate(tcp(2000), uid = 10_001))
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun udp_over_the_limit_evicts_the_oldest_idle_session_first() {
        val t = table(udp = 3)
        val oldest = t.tryCreate(udp(1000), uid = 10_001)!!.idleFor(60_000)
        t.tryCreate(udp(1001), uid = 10_001)!!.idleFor(30_000)
        t.tryCreate(udp(1002), uid = 10_001)!!.idleFor(20_000)
        assertNotNull(t.tryCreate(udp(2000), uid = 10_001))
        assertTrue("oldest idle session went first", udp(1000) in evicted)
        assertTrue(oldest.isClosed)
        assertTrue(refused.isEmpty())
        assertTrue(t.size <= 3)
    }

    @Test
    fun udp_over_the_limit_with_no_idle_session_is_refused() {
        val t = table(udp = 2)
        repeat(2) { t.tryCreate(udp(1000 + it), uid = 10_001) }   // all active just now
        assertNull(t.tryCreate(udp(2000), uid = 10_001))
        assertEquals(listOf(udp(2000)), refused)
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun one_app_cannot_take_every_slot() {
        val t = table(tcp = 100, udp = 100, perApp = 3)
        repeat(3) { assertNotNull(t.tryCreate(tcp(1000 + it), uid = 10_001)) }
        assertNull("app at its limit", t.tryCreate(tcp(2000), uid = 10_001))
        assertNotNull("another app still connects", t.tryCreate(tcp(3000), uid = 10_002))
    }

    @Test
    fun an_app_at_its_limit_makes_room_from_its_own_idle_udp_only() {
        val t = table(tcp = 100, udp = 100, perApp = 2)
        t.tryCreate(udp(1000), uid = 10_002)!!.idleFor(60_000)        // another app's idle session
        t.tryCreate(udp(1001), uid = 10_001)!!.idleFor(30_000)
        t.tryCreate(tcp(1002), uid = 10_001)
        assertNotNull(t.tryCreate(tcp(2000), uid = 10_001))
        assertEquals(listOf(udp(1001)), evicted)
        assertNotNull("the other app's session stays", t.get(udp(1000)))
    }

    @Test
    fun slots_come_back_when_sessions_end() {
        val t = table(tcp = 2)
        repeat(2) { t.tryCreate(tcp(1000 + it), uid = 10_001) }
        t.remove(tcp(1000))
        assertNotNull(t.tryCreate(tcp(2000), uid = 10_001))
        t.get(tcp(1001))!!.close()
        t.reap()
        assertNotNull(t.tryCreate(tcp(2001), uid = 10_001))
        assertEquals(2, t.size)
    }

    @Test
    fun an_existing_key_is_returned_without_counting_twice() {
        val t = table(tcp = 1)
        val s = t.tryCreate(tcp(1000), uid = 10_001)
        assertSame(s, t.tryCreate(tcp(1000), uid = 10_001))
        assertTrue(refused.isEmpty())
    }

    @Test
    fun clear_frees_every_slot() {
        val t = table(tcp = 2, perApp = 2)
        repeat(2) { t.tryCreate(tcp(1000 + it), uid = 10_001) }
        t.clear()
        repeat(2) { assertNotNull(t.tryCreate(tcp(2000 + it), uid = 10_001)) }
    }

    @Test
    fun defaults_are_the_agreed_limits() {
        val d = SessionTable.Limits()
        assertEquals(4_096, d.maxTcp)
        assertEquals(4_096, d.maxUdp)
        assertEquals(1_024, d.maxPerApp)
    }
}
