package com.privacyguard.app.ui.rules

import com.privacyguard.core.filter.FilterRule
import com.privacyguard.vpn.forwarder.NameBlockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Rules and allowed sites: input checks and row words (DESIGN_SPEC_1.1.md R16). */
class RulesLogicTest {

    private fun rule(
        action: FilterRule.Action = FilterRule.Action.DENY,
        domain: String? = null,
        pkg: String? = null,
        background: Boolean? = null,
        overrides: Boolean = false,
        label: String = "label",
    ) = FilterRule(
        id = "r", label = label, action = action, source = FilterRule.Source.USER,
        priority = FilterRule.HIGH_PRIORITY, matchDomain = domain, matchPackage = pkg,
        matchBackground = background, overridesSecurity = overrides,
    )

    @Test
    fun a_typed_name_is_normalized_like_every_name_the_engine_matches() {
        // CLAUDE.md §5.3: a trailing dot once bypassed user rules.
        assertEquals("ads.example.com", RulesLogic.parseSite("  Ads.Example.COM. "))
        assertEquals(NameBlockPolicy.normalize("Ads.Example.COM."), RulesLogic.parseSite("Ads.Example.COM."))
    }

    @Test
    fun an_address_pasted_from_the_browser_becomes_its_site_name() {
        assertEquals("ads.example.com", RulesLogic.parseSite("https://ads.example.com/path?q=1#x"))
        assertEquals("ads.example.com", RulesLogic.parseSite("http://user@ads.example.com:8080/"))
        assertEquals("example.com", RulesLogic.parseSite("example.com/"))
    }

    @Test
    fun the_wildcard_is_kept_and_a_top_level_domain_is_allowed_for_own_rules() {
        assertEquals("*.example.com", RulesLogic.parseSite("*.Example.com"))
        assertEquals("*.xyz", RulesLogic.parseSite("*.xyz"))
    }

    @Test
    fun an_international_name_is_stored_in_the_ascii_form_the_engine_sees() {
        assertEquals("xn--bcher-kva.de", RulesLogic.parseSite("bücher.de"))
    }

    @Test
    fun values_that_are_no_site_name_are_refused() {
        listOf("", "   ", "ads example.com", "-bad.com", "bad-.com", "a..b", "*.", "1.2.3.4", "::1", "ex_ample.com", "a".repeat(64) + ".com")
            .forEach { assertNull(it, RulesLogic.parseSite(it)) }
    }

    @Test
    fun package_names_follow_androids_rule() {
        assertTrue(RulesLogic.isValidPackage("com.whatsapp"))
        assertTrue(RulesLogic.isValidPackage(" org.example_app.v2 "))
        listOf("", "whatsapp", "com.", ".com", "com.1app", "com.what app", "com.wh-atsapp").forEach {
            assertFalse(it, RulesLogic.isValidPackage(it))
        }
    }

    @Test
    fun an_empty_field_is_not_an_error_but_cannot_be_added() {
        RulesLogic.Kind.entries.forEach { kind ->
            assertFalse(RulesLogic.showsError(kind, ""))
            assertFalse(RulesLogic.isValid(kind, ""))
        }
        assertTrue(RulesLogic.showsError(RulesLogic.Kind.ADDRESS, "2001:db8::1"))
        assertTrue(RulesLogic.isValid(RulesLogic.Kind.ADDRESS, " 10.0.0.0/8 "))
    }

    @Test
    fun the_effect_words_tell_a_malware_override_apart_from_an_ordinary_allow() {
        assertEquals(RulesLogic.Effect.BLOCK, RulesLogic.effectOf(rule()))
        assertEquals(RulesLogic.Effect.BLOCK_IN_BACKGROUND, RulesLogic.effectOf(rule(pkg = "com.a.b", background = true)))
        assertEquals(RulesLogic.Effect.ALLOW, RulesLogic.effectOf(rule(FilterRule.Action.ALLOW, domain = "a.com")))
        assertEquals(RulesLogic.Effect.ALLOW_DESPITE_MALWARE, RulesLogic.effectOf(rule(FilterRule.Action.ALLOW, domain = "a.com", overrides = true)))
    }

    @Test
    fun search_finds_a_rule_by_its_app_name_in_any_language() {
        val r = rule(pkg = "com.whatsapp", label = "Bloquer WhatsApp")
        assertTrue(RulesLogic.matches(r, "whats", appName = "WhatsApp"))
        assertTrue(RulesLogic.matches(r, "com.whatsapp", appName = null))
        assertTrue(RulesLogic.matches(r, "  ", appName = null))
        assertFalse(RulesLogic.matches(r, "telegram", appName = "WhatsApp"))
    }
}
