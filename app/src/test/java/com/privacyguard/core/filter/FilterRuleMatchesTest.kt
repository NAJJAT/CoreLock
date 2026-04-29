package com.privacyguard.core.filter

import com.privacyguard.core.metadata.EncryptionStatus
import org.junit.Assert.*
import org.junit.Test

class FilterRuleMatchesTest {

    private fun rule(
        matchDomain:     String?           = null,
        matchPackage:    String?           = null,
        matchIp:         String?           = null,
        matchPort:       Int?              = null,
        matchEncryption: EncryptionStatus? = null,
        matchBackground: Boolean?          = null,
    ) = FilterRule(
        id = "t", label = "test",
        action = FilterRule.Action.DENY,
        source = FilterRule.Source.USER,
        matchDomain     = matchDomain,
        matchPackage    = matchPackage,
        matchIp         = matchIp,
        matchPort       = matchPort,
        matchEncryption = matchEncryption,
        matchBackground = matchBackground,
    )

    // ── Domain matching ───────────────────────────────────────────────────────

    @Test
    fun exact_domain_matches() {
        assertTrue(rule(matchDomain = "example.com")
            .matches(-1, null, "example.com", "1.2.3.4", 443, 6))
    }

    @Test
    fun exact_domain_case_insensitive() {
        assertTrue(rule(matchDomain = "EXAMPLE.COM")
            .matches(-1, null, "example.com", "1.2.3.4", 80, 6))
    }

    @Test
    fun exact_domain_mismatch_returns_false() {
        assertFalse(rule(matchDomain = "example.com")
            .matches(-1, null, "other.com", "1.2.3.4", 80, 6))
    }

    @Test
    fun wildcard_domain_matches_subdomain() {
        assertTrue(rule(matchDomain = "*.example.com")
            .matches(-1, null, "sub.example.com", "1.2.3.4", 443, 6))
    }

    @Test
    fun wildcard_domain_matches_base_domain() {
        assertTrue(rule(matchDomain = "*.example.com")
            .matches(-1, null, "example.com", "1.2.3.4", 443, 6))
    }

    @Test
    fun wildcard_domain_does_not_match_different_tld() {
        assertFalse(rule(matchDomain = "*.example.com")
            .matches(-1, null, "example.org", "1.2.3.4", 443, 6))
    }

    @Test
    fun null_domain_does_not_match_domain_rule() {
        assertFalse(rule(matchDomain = "example.com")
            .matches(-1, null, null, "1.2.3.4", 80, 6))
    }

    // ── IP / CIDR matching ────────────────────────────────────────────────────

    @Test
    fun exact_ip_matches() {
        assertTrue(rule(matchIp = "10.0.0.1")
            .matches(-1, null, null, "10.0.0.1", 80, 6))
    }

    @Test
    fun exact_ip_mismatch_returns_false() {
        assertFalse(rule(matchIp = "10.0.0.1")
            .matches(-1, null, null, "10.0.0.2", 80, 6))
    }

    @Test
    fun cidr_matches_ip_in_subnet() {
        assertTrue(rule(matchIp = "10.0.0.0/8")
            .matches(-1, null, null, "10.255.255.1", 80, 6))
    }

    @Test
    fun cidr_does_not_match_ip_outside_subnet() {
        assertFalse(rule(matchIp = "10.0.0.0/8")
            .matches(-1, null, null, "11.0.0.1", 80, 6))
    }

    @Test
    fun cidr_slash_32_matches_only_exact_ip() {
        assertTrue(rule(matchIp = "192.168.1.5/32")
            .matches(-1, null, null, "192.168.1.5", 443, 6))
        assertFalse(rule(matchIp = "192.168.1.5/32")
            .matches(-1, null, null, "192.168.1.6", 443, 6))
    }

    // ── Port matching ─────────────────────────────────────────────────────────

    @Test
    fun port_rule_matches_specific_port() {
        assertTrue(rule(matchPort = 8080).matches(-1, null, null, "1.2.3.4", 8080, 6))
    }

    @Test
    fun port_rule_mismatches_different_port() {
        assertFalse(rule(matchPort = 8080).matches(-1, null, null, "1.2.3.4", 80, 6))
    }

    // ── Encryption matching ───────────────────────────────────────────────────

    @Test
    fun cleartext_rule_matches_cleartext() {
        assertTrue(rule(matchEncryption = EncryptionStatus.CLEARTEXT)
            .matches(-1, null, null, "1.2.3.4", 80, 6, EncryptionStatus.CLEARTEXT))
    }

    @Test
    fun cleartext_rule_does_not_match_tls() {
        assertFalse(rule(matchEncryption = EncryptionStatus.CLEARTEXT)
            .matches(-1, null, null, "1.2.3.4", 443, 6, EncryptionStatus.TLS_1_2))
    }

    // ── Compound / edge cases ─────────────────────────────────────────────────

    @Test
    fun all_criteria_must_match() {
        val r = rule(matchPackage = "com.app", matchPort = 443)
        assertFalse(r.matches(-1, "com.app", null, "1.2.3.4", 80, 6))    // wrong port
        assertFalse(r.matches(-1, "com.other", null, "1.2.3.4", 443, 6)) // wrong pkg
        assertTrue(r.matches(-1, "com.app", null, "1.2.3.4", 443, 6))    // both match
    }

    @Test
    fun disabled_rule_never_matches() {
        val r = rule(matchDomain = "example.com").copy(isEnabled = false)
        assertFalse(r.matches(-1, null, "example.com", "1.2.3.4", 80, 6))
    }

    @Test
    fun rule_with_no_criteria_matches_everything() {
        val r = rule()
        assertTrue(r.matches(-1, null, null, "1.2.3.4", 80, 6))
        assertTrue(r.matches(1000, "com.any", "example.com", "9.9.9.9", 443, 17))
    }
}
