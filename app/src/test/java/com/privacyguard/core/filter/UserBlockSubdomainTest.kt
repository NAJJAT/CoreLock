package com.privacyguard.core.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0017: a user Block rule for a name covers its subdomains; an Allow rule stays exact. */
class UserBlockSubdomainTest {

    private fun userBlock(d: String) = FilterRule.blockDomain(d, source = FilterRule.Source.USER, priority = FilterRule.HIGH_PRIORITY)
    private fun FilterRule.on(name: String) = matches(10_123, "com.example", name, "93.184.216.34", 443, 6)

    @Test fun a_user_block_covers_the_name_and_its_subdomains() {
        val r = userBlock("example.com")
        assertTrue(r.on("example.com"))
        assertTrue(r.on("www.example.com"))
        assertTrue(r.on("A.B.Example.COM."))
    }

    @Test fun it_matches_whole_labels_only() {
        val r = userBlock("example.com")
        assertFalse(r.on("badexample.com"))
        assertFalse(r.on("example.com.evil.net"))
        assertFalse(r.on("com"))
    }

    @Test fun allow_rules_stay_exact() {
        val r = FilterRule.allowDomain("example.com")
        assertTrue(r.on("example.com"))
        assertFalse(r.on("ads.example.com"))
    }

    @Test fun precedence_is_unchanged_a_longer_allow_keeps_one_subdomain_open() {
        val engine = FilterEngine().apply {
            addRule(userBlock("example.com"))
            addRule(FilterRule.allowDomain("www.example.com"))
        }
        fun blocked(n: String) = engine.evaluate(10_123, "com.example", n, "93.184.216.34", 443, 6).isBlocked
        assertTrue(blocked("example.com"))
        assertTrue(blocked("cdn.example.com"))
        assertFalse(blocked("www.example.com"))
        assertTrue("the Allow is exact: its subdomains stay blocked", blocked("x.www.example.com"))
    }
}
