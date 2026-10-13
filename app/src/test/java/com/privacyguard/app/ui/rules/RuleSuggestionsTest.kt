package com.privacyguard.app.ui.rules

import com.privacyguard.app.ui.rules.RuleSuggestions.Kind
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSuggestionsTest {

    private val wa = "com.whatsapp"

    private fun deny(pkg: String? = null, enc: EncryptionStatus? = null, bg: Boolean? = null) = FilterRule(
        id = "r", label = "", action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
        matchPackage = pkg, matchEncryption = enc, matchBackground = bg,
    )

    private fun build(rules: List<FilterRule> = emptyList(), dismissed: Set<String> = emptySet()) =
        RuleSuggestions.build(mapOf(wa to 17), mapOf(wa to 250), rules, dismissed)

    @Test
    fun suggestions_are_narrow_never_a_whole_app_block() {
        val s = build()
        assertEquals(listOf(Kind.CLEARTEXT, Kind.BACKGROUND), s.map { it.kind })
        val cleartext = RuleSuggestions.ruleFor(s[0], "id", "l")
        assertEquals(EncryptionStatus.CLEARTEXT, cleartext.matchEncryption)
        assertNull(cleartext.matchBackground)
        val background = RuleSuggestions.ruleFor(s[1], "id", "l")
        assertEquals(true, background.matchBackground)
        assertNull(background.matchEncryption)
    }

    @Test
    fun a_weak_tls_rule_does_not_hide_the_cleartext_suggestion() {
        assertTrue(build(listOf(deny(enc = EncryptionStatus.WEAK_TLS))).any { it.kind == Kind.CLEARTEXT })
    }

    @Test
    fun a_cleartext_block_for_everyone_or_this_app_hides_it() {
        assertTrue(build(listOf(deny(enc = EncryptionStatus.CLEARTEXT))).none { it.kind == Kind.CLEARTEXT })
        assertTrue(build(listOf(deny(pkg = wa, enc = EncryptionStatus.CLEARTEXT))).none { it.kind == Kind.CLEARTEXT })
    }

    @Test
    fun a_blocked_app_gets_no_suggestions() {
        assertTrue(build(listOf(deny(pkg = wa))).isEmpty())
    }

    @Test
    fun dismissed_suggestions_stay_away() {
        val dismissed = build().map { it.id }.toSet()
        assertTrue(build(dismissed = dismissed).isEmpty())
    }

    @Test
    fun small_counts_are_not_suggested() {
        val s = RuleSuggestions.build(mapOf(wa to 2), mapOf(wa to 99), emptyList(), emptySet())
        assertTrue(s.isEmpty())
    }
}
