package com.privacyguard.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bug-fix round 2: S-1 (a rule's hit count per packet) and S-2 (no hard cap). */
class BlockAttemptsTest {

    private fun key(n: Int, port: Int = 853) =
        SessionKey.of("10.0.0.2", 40_000 + (n % 20_000), "198.51.${(n / 250) % 250}.${n % 250}", port, SessionKey.PROTO_UDP)

    @Test
    fun retransmissions_within_the_window_are_one_attempt() {
        val a = BlockAttempts(windowMs = 30_000)
        assertTrue(a.record(key(1), 0))
        assertFalse(a.record(key(1), 1_000))
        assertFalse(a.record(key(1), 29_000))
        assertTrue("a different tuple is a new attempt", a.record(key(2), 29_000))
    }

    /** S-2: a burst of distinct blocked tuples inside the window must not grow the table past its cap. */
    @Test
    fun the_table_has_a_hard_cap_even_when_every_entry_is_recent() {
        val a = BlockAttempts(windowMs = 30_000, maxKeys = 1_000)
        for (n in 0 until 10_000) a.record(key(n), 1_000L + n % 100)   // all well inside 30 s
        assertTrue("size ${a.size}", a.size <= 1_000)
    }

    @Test
    fun past_the_cap_the_oldest_tuple_is_forgotten_first() {
        val a = BlockAttempts(windowMs = 30_000, maxKeys = 3)
        a.record(key(1), 0); a.record(key(2), 1); a.record(key(3), 2)
        a.record(key(4), 3)                                   // pushes out key 1
        assertTrue("key 1 was evicted, so it is a new attempt again", a.record(key(1), 4))
        assertFalse("key 4 is still known", a.record(key(4), 5))
    }

    /** S-1: one hit per attempt, the same 30 s window as the history row, not one per retransmitted packet. */
    @Test
    fun a_rule_hit_counts_once_per_attempt_not_per_packet() {
        val a = BlockAttempts(windowMs = 30_000)
        var hits = 0
        // How the service calls it: countsAsHit first (onBlocked), then record (logBlockedAttempt).
        for (t in 0L until 60_000L step 500) {
            if (a.countsAsHit(key(7), t)) hits++
            a.record(key(7), t)
        }
        // Packets every 0.5 s for 60 s on one tuple: the window restarts on each packet, so this is one attempt.
        assertEquals(1, hits)
        // A pause longer than the window: a new attempt, one more hit.
        if (a.countsAsHit(key(7), 60_000L + 30_000)) hits++
        assertEquals(2, hits)
    }
}
