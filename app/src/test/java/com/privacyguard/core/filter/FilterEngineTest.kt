package com.privacyguard.core.filter

import com.privacyguard.core.metadata.EncryptionStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FilterEngineTest {

    private lateinit var engine: FilterEngine

    @Before
    fun setUp() {
        engine = FilterEngine()
    }

    // ── Default behaviour ─────────────────────────────────────────────────────

    @Test
    fun no_rules_allows_by_default() {
        val d = engine.evaluate(-1, null, "google.com", "1.2.3.4", 443, 6)
        assertEquals(FilterRule.Action.ALLOW, d.action)
        assertNull(d.matchedRule)
    }

    @Test
    fun deny_default_blocks_when_no_rules_match() {
        val e = FilterEngine(defaultAction = FilterRule.Action.DENY)
        assertEquals(FilterRule.Action.DENY,
            e.evaluate(-1, null, "example.com", "1.2.3.4", 80, 6).action)
    }

    // ── Single-rule matching ──────────────────────────────────────────────────

    @Test
    fun deny_domain_rule_blocks_matching_domain() {
        engine.addRule(FilterRule.blockDomain("evil.com"))
        assertEquals(FilterRule.Action.DENY,
            engine.evaluate(-1, null, "evil.com", "1.2.3.4", 80, 6).action)
    }

    @Test
    fun deny_rule_does_not_block_different_domain() {
        engine.addRule(FilterRule.blockDomain("evil.com"))
        assertEquals(FilterRule.Action.ALLOW,
            engine.evaluate(-1, null, "good.com", "1.2.3.4", 80, 6).action)
    }

    @Test
    fun package_rule_blocks_exact_package() {
        engine.addRule(FilterRule.blockPackage("com.evil.app"))
        assertEquals(FilterRule.Action.DENY,
            engine.evaluate(-1, "com.evil.app", "example.com", "1.2.3.4", 443, 6).action)
    }

    @Test
    fun package_rule_does_not_block_other_package() {
        engine.addRule(FilterRule.blockPackage("com.evil.app"))
        assertEquals(FilterRule.Action.ALLOW,
            engine.evaluate(-1, "com.good.app", "example.com", "1.2.3.4", 443, 6).action)
    }

    @Test
    fun ip_rule_blocks_exact_ip() {
        engine.addRule(FilterRule.blockIp("10.0.0.1"))
        assertEquals(FilterRule.Action.DENY,
            engine.evaluate(-1, null, null, "10.0.0.1", 80, 6).action)
    }

    @Test
    fun cidr_rule_blocks_ip_inside_subnet_and_allows_outside() {
        engine.addRule(FilterRule(id = "cidr", label = "Block /24",
            action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
            matchIp = "192.168.1.0/24"))
        assertEquals(FilterRule.Action.DENY,
            engine.evaluate(-1, null, null, "192.168.1.100", 80, 6).action)
        assertEquals(FilterRule.Action.ALLOW,
            engine.evaluate(-1, null, null, "192.168.2.1", 80, 6).action)
    }

    // ── Encryption rules ──────────────────────────────────────────────────────

    @Test
    fun cleartext_rule_blocks_cleartext_connections() {
        engine.addRule(FilterRule.blockCleartext())
        assertEquals(FilterRule.Action.DENY,
            engine.evaluate(-1, null, "example.com", "1.2.3.4", 80, 6,
                encStatus = EncryptionStatus.CLEARTEXT).action)
    }

    /** Regression: TcpForwarder's cleartext check passes the host as the app wrote it. */
    @Test
    fun cleartext_check_matches_a_domain_rule_despite_case_and_trailing_dot() {
        engine.addRule(FilterRule.blockDomain("tracker.example", source = FilterRule.Source.USER))
        assertEquals(FilterRule.Action.DENY,
            engine.evaluate(-1, null, "Tracker.Example.", "1.2.3.4", 80, 6,
                encStatus = EncryptionStatus.CLEARTEXT).action)
    }

    @Test
    fun cleartext_rule_allows_encrypted_connections() {
        engine.addRule(FilterRule.blockCleartext())
        assertEquals(FilterRule.Action.ALLOW,
            engine.evaluate(-1, null, "example.com", "1.2.3.4", 443, 6,
                encStatus = EncryptionStatus.TLS_1_2).action)
    }

    @Test
    fun weak_tls_rule_blocks_weak_tls() {
        engine.addRule(FilterRule.blockWeakTls())
        assertEquals(FilterRule.Action.DENY,
            engine.evaluate(-1, null, "example.com", "1.2.3.4", 443, 6,
                encStatus = EncryptionStatus.WEAK_TLS).action)
    }

    // ── Background rules ──────────────────────────────────────────────────────

    @Test
    fun background_rule_fires_only_for_background_connections() {
        engine.addRule(FilterRule(id = "bg", label = "Block background",
            action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
            matchPackage = "com.app", matchBackground = true))
        assertEquals(FilterRule.Action.ALLOW,
            engine.evaluate(-1, "com.app", null, "1.2.3.4", 443, 6, isBackground = false).action)
        assertEquals(FilterRule.Action.DENY,
            engine.evaluate(-1, "com.app", null, "1.2.3.4", 443, 6, isBackground = true).action)
    }

    // ── Priority ──────────────────────────────────────────────────────────────

    @Test
    fun lower_priority_number_wins() {
        engine.setRules(listOf(
            FilterRule(id = "deny", label = "Deny", action = FilterRule.Action.DENY,
                source = FilterRule.Source.BLOCKLIST, priority = 100,
                matchDomain = "api.example.com"),
            FilterRule(id = "allow", label = "Allow", action = FilterRule.Action.ALLOW,
                source = FilterRule.Source.USER, priority = 10,
                matchDomain = "api.example.com"),
        ))
        val d = engine.evaluate(-1, null, "api.example.com", "1.2.3.4", 443, 6)
        assertEquals(FilterRule.Action.ALLOW, d.action)
        assertEquals("allow", d.matchedRule?.id)
    }

    // ── Disabled rules ────────────────────────────────────────────────────────

    @Test
    fun disabled_rule_is_skipped() {
        engine.addRule(FilterRule(id = "r1", label = "Block example.com",
            action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
            isEnabled = false, matchDomain = "example.com"))
        assertEquals(FilterRule.Action.ALLOW,
            engine.evaluate(-1, null, "example.com", "1.2.3.4", 80, 6).action)
    }

    @Test
    fun set_enabled_toggles_rule_at_runtime() {
        engine.addRule(FilterRule(id = "tog", label = "Toggleable",
            action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
            matchDomain = "example.com"))
        engine.setEnabled("tog", false)
        assertEquals(FilterRule.Action.ALLOW,
            engine.evaluate(-1, null, "example.com", "1.2.3.4", 80, 6).action)
        engine.setEnabled("tog", true)
        assertEquals(FilterRule.Action.DENY,
            engine.evaluate(-1, null, "example.com", "1.2.3.4", 80, 6).action)
    }

    // ── Rule management ───────────────────────────────────────────────────────

    @Test
    fun rule_count_matches_added_rules() {
        engine.addRule(FilterRule.blockDomain("a.com"))
        engine.addRule(FilterRule.blockDomain("b.com"))
        assertEquals(2, engine.ruleCount)
    }

    @Test
    fun remove_rule_removes_only_that_rule() {
        val r = FilterRule.blockDomain("evil.com")
        engine.addRule(r)
        engine.addRule(FilterRule.blockDomain("another.com"))
        engine.removeRule(r.id)
        assertEquals(FilterRule.Action.ALLOW,
            engine.evaluate(-1, null, "evil.com", "1.2.3.4", 80, 6).action)
        assertEquals(FilterRule.Action.DENY,
            engine.evaluate(-1, null, "another.com", "1.2.3.4", 80, 6).action)
    }

    @Test
    fun clear_rules_leaves_engine_empty() {
        engine.addRule(FilterRule.blockDomain("a.com"))
        engine.clearRules()
        assertEquals(0, engine.ruleCount)
        assertEquals(FilterRule.Action.ALLOW,
            engine.evaluate(-1, null, "a.com", "1.2.3.4", 80, 6).action)
    }

    // ── isDomainBlocked convenience ───────────────────────────────────────────

    @Test
    fun is_domain_blocked_true_for_matching_deny_rule() {
        engine.addRule(FilterRule.blockDomain("tracker.net"))
        assertTrue(engine.isDomainBlocked("tracker.net"))
    }

    @Test
    fun is_domain_blocked_false_when_no_matching_rule() {
        engine.addRule(FilterRule.blockDomain("tracker.net"))
        assertFalse(engine.isDomainBlocked("safe.com"))
    }

    // ── User Allow for an address (opens a DoH address) ──────────────────────

    private fun ipRule(ip: String, action: FilterRule.Action, source: FilterRule.Source = FilterRule.Source.USER, pkg: String? = null) =
        FilterRule(id = "ip-$ip-$action-$pkg", label = "ip", action = action, source = source, matchIp = ip, matchPackage = pkg)

    @Test
    fun user_allow_for_an_address_is_found() {
        engine.addRule(ipRule("1.1.1.1", FilterRule.Action.ALLOW))
        engine.addRule(ipRule("9.9.9.0/24", FilterRule.Action.ALLOW))
        assertTrue(engine.userAllowsAddress("1.1.1.1", 443, 6))
        assertTrue(engine.userAllowsAddress("9.9.9.9", 443, 17))
        assertFalse(engine.userAllowsAddress("8.8.8.8", 443, 6))
    }

    // ── Conflicting user rules (all made in the app at HIGH_PRIORITY) ───────────

    private fun userRule(
        action: FilterRule.Action, domain: String? = null, ip: String? = null,
        pkg: String? = null, background: Boolean? = null,
    ) = FilterRule(
        id = "u-$action-$domain-$ip-$pkg-$background", label = "user", action = action,
        source = FilterRule.Source.USER, priority = FilterRule.HIGH_PRIORITY,
        matchDomain = domain, matchIp = ip, matchPackage = pkg, matchBackground = background,
    )

    /** The winner must not depend on which rule was added (or loaded) first. */
    private fun assertWinsInEitherOrder(winner: FilterRule, loser: FilterRule, check: (FilterEngine) -> FilterRule?) {
        for (order in listOf(listOf(winner, loser), listOf(loser, winner))) {
            assertEquals("setRules ${order.map { it.id }}", winner, check(FilterEngine().apply { setRules(order) }))
            assertEquals("addRule ${order.map { it.id }}", winner, check(FilterEngine().apply { order.forEach(::addRule) }))
        }
    }

    @Test
    fun exact_block_beats_wildcard_allow() {
        assertWinsInEitherOrder(
            userRule(FilterRule.Action.DENY, domain = "ads.example.com"),
            userRule(FilterRule.Action.ALLOW, domain = "*.example.com"),
        ) { it.domainRuleFor("ads.example.com", null) }
    }

    @Test
    fun longer_wildcard_beats_shorter() {
        assertWinsInEitherOrder(
            userRule(FilterRule.Action.ALLOW, domain = "*.cdn.example.com"),
            userRule(FilterRule.Action.DENY, domain = "*.example.com"),
        ) { it.domainRuleFor("img.cdn.example.com", null) }
    }

    @Test
    fun app_specific_rule_beats_every_app_rule() {
        assertWinsInEitherOrder(
            userRule(FilterRule.Action.DENY, domain = "x.com", pkg = "com.example.a"),
            userRule(FilterRule.Action.ALLOW, domain = "x.com"),
        ) { it.domainRuleFor("x.com", "com.example.a") }
    }

    @Test
    fun allow_wins_between_equally_specific_rules() {
        val allow = userRule(FilterRule.Action.ALLOW, domain = "x.com")
        assertWinsInEitherOrder(allow, userRule(FilterRule.Action.DENY, domain = "x.com")) {
            it.domainRuleFor("x.com", null)
        }
        assertWinsInEitherOrder(allow, userRule(FilterRule.Action.DENY, domain = "X.com.")) {
            it.domainRuleFor("x.com", null)
        }
    }

    @Test
    fun exact_address_beats_a_range_and_a_longer_prefix_beats_a_shorter() {
        assertWinsInEitherOrder(
            userRule(FilterRule.Action.ALLOW, ip = "10.1.2.3"),
            userRule(FilterRule.Action.DENY, ip = "10.0.0.0/8"),
        ) { it.evaluate(-1, null, null, "10.1.2.3", 443, 6).matchedRule }
        assertWinsInEitherOrder(
            userRule(FilterRule.Action.DENY, ip = "10.1.0.0/16"),
            userRule(FilterRule.Action.ALLOW, ip = "10.0.0.0/8"),
        ) { it.evaluate(-1, null, null, "10.1.2.3", 443, 6).matchedRule }
    }

    @Test
    fun background_block_beats_allowing_the_app_only_in_the_background() {
        val bgBlock = userRule(FilterRule.Action.DENY, pkg = "com.example.a", background = true)
        val allowApp = userRule(FilterRule.Action.ALLOW, pkg = "com.example.a")
        assertWinsInEitherOrder(bgBlock, allowApp) {
            it.evaluate(10_123, "com.example.a", null, "1.2.3.4", 443, 6, isBackground = true).matchedRule
        }
        assertWinsInEitherOrder(allowApp, bgBlock) {
            it.evaluate(10_123, "com.example.a", null, "1.2.3.4", 443, 6, isBackground = false).matchedRule
        }
    }

    @Test
    fun priority_still_comes_before_specificity() {
        val userAllow = userRule(FilterRule.Action.ALLOW, domain = "*.example.com")
        val listBlock = FilterRule.blockDomain("ads.example.com")   // DEFAULT_PRIORITY
        assertWinsInEitherOrder(userAllow, listBlock) { it.domainRuleFor("ads.example.com", null) }
    }

    @Test
    fun only_an_app_wide_user_allow_opens_an_address() {
        engine.addRule(ipRule("1.1.1.1", FilterRule.Action.ALLOW, pkg = "com.example.browser"))
        engine.addRule(ipRule("8.8.8.8", FilterRule.Action.ALLOW, source = FilterRule.Source.SYSTEM))
        engine.addRule(ipRule("9.9.9.9", FilterRule.Action.DENY))
        assertFalse("app-specific", engine.userAllowsAddress("1.1.1.1", 443, 6))
        assertFalse("not the user's", engine.userAllowsAddress("8.8.8.8", 443, 6))
        assertFalse("a block", engine.userAllowsAddress("9.9.9.9", 443, 6))
    }
}
