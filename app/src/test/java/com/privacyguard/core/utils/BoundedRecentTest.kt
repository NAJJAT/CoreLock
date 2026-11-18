package com.privacyguard.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bug-fix round 2: hard caps for the service's per-tuple table and "already notified" sets. */
class BoundedRecentTest {

    @Test
    fun the_set_keeps_at_most_its_capacity_and_drops_the_oldest_first() {
        val s = BoundedSet<String>(3)
        assertTrue(s.add("a")); assertTrue(s.add("b")); assertTrue(s.add("c"))
        assertFalse("already a member", s.add("a"))
        assertTrue(s.add("d"))                       // "a" was added first: it goes
        assertEquals(3, s.size)
        assertFalse("a" in s)
        assertTrue("b" in s && "c" in s && "d" in s)
        assertTrue("an evicted member can be added (and notified) again", s.add("a"))
    }

    @Test
    fun the_set_stays_bounded_under_a_stream_of_new_members() {
        val s = BoundedSet<String>(1_024)
        repeat(100_000) { s.add("host$it.example") }
        assertEquals(1_024, s.size)
    }

    @Test
    fun the_map_drops_the_least_recently_used_key_first() {
        val m = BoundedRecentMap<String>(2)
        assertNull(m.put("a", 1)); assertNull(m.put("b", 2))
        assertEquals(1L, m.put("a", 3))              // "a" is now the newest
        m.put("c", 4)                                // so "b" goes
        assertNull(m.get("b"))
        assertEquals(3L, m.get("a"))
        assertEquals(2, m.size)
    }

    /** The service's four "already told the user" sets are capped (they grew with every new name, package or hash). */
    @Test
    fun the_service_notification_sets_are_bounded() {
        val src = File("src/main/java/com/privacyguard/platform/android/PrivacyVpnService.kt").readText()
        for (name in listOf("ja3NotifiedHashes", "cleartextNotifiedPackages", "backgroundBlockNotifiedPackages", "ctNotifiedDomains")) {
            assertTrue("$name is a BoundedSet", Regex("""val $name = com\.privacyguard\.core\.utils\.BoundedSet<String>\(""").containsMatchIn(src))
        }
    }
}
