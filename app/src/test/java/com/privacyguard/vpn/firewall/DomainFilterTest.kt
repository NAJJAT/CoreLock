package com.privacyguard.vpn.firewall

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DomainFilterTest {

    private lateinit var filter: DomainFilter

    @Before
    fun setUp() {
        filter = DomainFilter()
    }

    // ── Exact match ───────────────────────────────────────────────────────────

    @Test
    fun exact_match_blocks_domain() {
        filter.addDomain("doubleclick.net")
        assertTrue(filter.isBlocked("doubleclick.net"))
    }

    @Test
    fun exact_match_does_not_block_different_domain() {
        filter.addDomain("doubleclick.net")
        assertFalse(filter.isBlocked("google.com"))
    }

    @Test
    fun exact_match_does_not_block_parent_domain() {
        filter.addDomain("sub.example.com")
        assertFalse(filter.isBlocked("example.com"))
    }

    // ── Wildcard ──────────────────────────────────────────────────────────────

    @Test
    fun wildcard_blocks_direct_subdomain() {
        filter.addDomain("*.example.com")
        assertTrue(filter.isBlocked("sub.example.com"))
    }

    @Test
    fun wildcard_blocks_base_domain() {
        filter.addDomain("*.example.com")
        assertTrue(filter.isBlocked("example.com"))
    }

    @Test
    fun wildcard_blocks_deep_subdomain() {
        filter.addDomain("*.example.com")
        assertTrue(filter.isBlocked("a.b.c.example.com"))
    }

    @Test
    fun wildcard_does_not_block_sibling_tld() {
        filter.addDomain("*.example.com")
        assertFalse(filter.isBlocked("example.org"))
    }

    @Test
    fun wildcard_prefix_blocks_subdomain_of_subdomain() {
        filter.addDomain("*.ads.example.com")
        assertTrue(filter.isBlocked("evil.ads.example.com"))
    }

    // ── Case insensitivity + normalisation ───────────────────────────────────

    @Test
    fun lookup_is_case_insensitive() {
        filter.addDomain("Tracker.Example.COM")
        assertTrue(filter.isBlocked("tracker.example.com"))
        assertTrue(filter.isBlocked("TRACKER.EXAMPLE.COM"))
    }

    @Test
    fun trailing_dot_in_query_is_trimmed() {
        filter.addDomain("example.com")
        assertTrue(filter.isBlocked("example.com."))
    }

    @Test
    fun empty_domain_returns_false() {
        assertFalse(filter.isBlocked(""))
    }

    // ── rebuild ───────────────────────────────────────────────────────────────

    @Test
    fun rebuild_atomically_replaces_list() {
        filter.addDomain("old.com")
        filter.rebuild(listOf("new.com"))
        assertFalse(filter.isBlocked("old.com"))
        assertTrue(filter.isBlocked("new.com"))
    }

    @Test
    fun rebuild_updates_inserted_count() {
        filter.rebuild(listOf("a.com", "b.com", "c.com"))
        assertEquals(3L, filter.insertedCount.get())
    }

    @Test
    fun rebuild_skips_blank_and_comment_lines() {
        filter.rebuild(listOf("# comment", "", "  ", "valid.com"))
        assertEquals(1L, filter.size)
        assertTrue(filter.isBlocked("valid.com"))
    }

    // ── clear / remove ────────────────────────────────────────────────────────

    @Test
    fun clear_empties_filter() {
        filter.addDomain("example.com")
        filter.clear()
        assertFalse(filter.isBlocked("example.com"))
        assertEquals(0L, filter.size)
    }

    @Test
    fun remove_domain_works() {
        filter.addDomain("example.com")
        assertTrue(filter.removeDomain("example.com"))
        assertFalse(filter.isBlocked("example.com"))
    }

    @Test
    fun remove_absent_domain_returns_false() {
        assertFalse(filter.removeDomain("nothere.com"))
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    @Test
    fun stats_count_blocked_and_total_lookups() {
        filter.rebuild(listOf("tracker.com"))
        filter.isBlocked("tracker.com")  // hit
        filter.isBlocked("tracker.com")  // hit
        filter.isBlocked("other.com")    // miss

        val s = filter.stats()
        assertEquals(3L, s.totalLookups)
        assertEquals(2L, s.blockedHits)
        assertEquals(1L, s.loadedDomains)
    }
}
