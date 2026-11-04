package com.privacyguard.app.ui.rules

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.core.filter.FilterRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Settings › Rules and allowed sites (DESIGN_SPEC_1.1.md R16, §5.6 parity row for RulesScreen). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RulesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val block = FilterRule(
        id = "b", label = "Block ads.example.com", action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
        priority = FilterRule.HIGH_PRIORITY, matchDomain = "ads.example.com", hitCount = 3,
    )
    private val risky = FilterRule(
        id = "r", label = "Allow bad.example (malware warning accepted)", action = FilterRule.Action.ALLOW,
        source = FilterRule.Source.USER, priority = FilterRule.HIGH_PRIORITY, matchDomain = "bad.example", overridesSecurity = true,
    )
    private val app = FilterRule(
        id = "a", label = "Block WhatsApp", action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
        priority = FilterRule.HIGH_PRIORITY, matchPackage = "com.whatsapp", isEnabled = false,
    )

    private fun loaded(vararg rules: FilterRule) = RulesUiState(
        loaded = true, rules = rules.toList(), hasAnyRule = rules.isNotEmpty(), appNames = mapOf("com.whatsapp" to "WhatsApp"),
    )

    private fun show(state: RulesUiState, actions: RulesActions = RulesActions()) = rule.setContent {
        CoreLockTheme(Appearance.DARK) { RulesContent(state, actions) }
    }

    @Test
    fun loading_then_error_with_try_again() {
        var retried = false
        show(RulesUiState(loaded = true, loadFailed = true), RulesActions(onRetry = { retried = true }))
        rule.onNodeWithText("Your rules couldn’t be loaded.").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertTrue(retried)
    }

    @Test
    fun no_rules_says_where_rules_come_from_and_offers_add() {
        show(loaded())
        rule.onNodeWithText("No rules yet. Allow or block a site from Activity, or add a rule here.").assertIsDisplayed()
        rule.onNodeWithText("Add rule").performClick()
        rule.onNodeWithTag(TAG_RULES_FIELD).assertIsDisplayed()
    }

    @Test
    fun each_rule_says_in_words_what_it_does_to_what() {
        show(loaded(block, app))
        rule.onNodeWithText("Blocks").assertIsDisplayed()
        rule.onNodeWithText("ads.example.com").assertIsDisplayed()
        rule.onNodeWithText("Triggered 3 times", substring = true, ignoreCase = true).assertIsDisplayed()
        // The app's name, not the package; and a switched-off rule says Off in words.
        rule.onNodeWithText("WhatsApp").assertIsDisplayed()
        rule.onNodeWithText("Blocks · Off").assertIsDisplayed()
        rule.onNodeWithTag(TAG_RULE_SWITCH + "b").assertIsOn()
        rule.onNodeWithTag(TAG_RULE_SWITCH + "a").assertIsOff()
    }

    @Test
    fun the_title_comes_from_the_rule_not_from_the_label_written_in_another_language() {
        // Made while the app was in Arabic: the stored label stays Arabic, the title does not.
        val arabic = block.copy(id = "x", label = "حظر ads.example.com")
        val forApp = block.copy(id = "y", label = "Bloquer ads.example.com pour WhatsApp", matchDomain = "t.example", matchPackage = "com.whatsapp")
        val portOnly = block.copy(id = "z", label = "Block unencrypted web", matchDomain = null, matchPort = 80)
        show(loaded(arabic, forApp, portOnly))
        rule.onNodeWithText("ads.example.com").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("حظر", substring = true).fetchSemanticsNodes().size)
        // A site rule for one app: the site is the title, the app is said below it.
        rule.onNodeWithText("t.example").assertIsDisplayed()
        rule.onNodeWithText("App: WhatsApp", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Site: t.example", substring = true).fetchSemanticsNodes().size)
        // Nothing to name it after: the stored label is the fallback.
        rule.onNodeWithText("Block unencrypted web").assertIsDisplayed()
    }

    @Test
    fun a_malware_override_never_reads_like_an_ordinary_allow() {
        show(loaded(risky))
        rule.onNodeWithText("Allows, despite the malware warning").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Allows").fetchSemanticsNodes().size)
    }

    @Test
    fun delete_and_switch_report_the_rule_they_belong_to() {
        var deleted: FilterRule? = null
        var toggled: Pair<String, Boolean>? = null
        show(loaded(block), RulesActions(onDelete = { deleted = it }, onToggle = { r, on -> toggled = r.id to on }))
        rule.onNodeWithContentDescription("Delete rule: ads.example.com").performClick()
        // The whole rule, so Undo can write it back with its id, priority and override flag.
        assertEquals(block, deleted)
        rule.onNodeWithTag(TAG_RULE_SWITCH + "b").performClick()
        assertEquals("b" to false, toggled)
    }

    @Test
    fun the_delete_target_is_at_least_48dp() {
        show(loaded(block))
        val size = rule.onNodeWithTag(TAG_RULE_DELETE + "b").fetchSemanticsNode().size
        val density = rule.density.density
        assertTrue("delete is ${size.width / density} dp wide", size.width / density >= 48f && size.height / density >= 48f)
    }

    @Test
    fun a_search_without_a_match_says_so_instead_of_no_rules_yet() {
        show(loaded().copy(hasAnyRule = true, query = "zzz"))
        rule.onNodeWithText("No rule matches “zzz”.").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("No rules yet", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun add_is_enabled_only_for_a_valid_value_and_explains_a_wrong_one() {
        show(loaded(block))
        rule.onNodeWithTag(TAG_RULES_ADD).performClick()
        rule.onNodeWithTag(TAG_RULES_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_RULES_FIELD).performTextInput("not a site")
        rule.onNodeWithText("That isn’t a site name. Write it like example.com.").assertIsDisplayed()
        rule.onNodeWithTag(TAG_RULES_SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun add_sends_kind_value_and_action() {
        var added: Triple<RulesLogic.Kind, String, FilterRule.Action>? = null
        show(loaded(block), RulesActions(onAdd = { k, v, a -> added = Triple(k, v, a) }))
        rule.onNodeWithTag(TAG_RULES_ADD).performClick()
        rule.onNodeWithText("Address").performClick()
        rule.onNodeWithTag(TAG_RULES_FIELD).performTextInput("10.0.0.0/8")
        rule.onNodeWithText("Allow").performClick()
        rule.onNodeWithText("Allow wins over the ads and trackers lists. For a site on a malware list, CoreLock warns you first.").assertIsDisplayed()
        rule.onNodeWithTag(TAG_RULES_SUBMIT).performScrollTo().assertIsEnabled().performClick()
        assertEquals(Triple(RulesLogic.Kind.ADDRESS, "10.0.0.0/8", FilterRule.Action.ALLOW), added)
        // The sheet closes after Add.
        assertEquals(0, rule.onAllNodes(hasTestTag(TAG_RULES_FIELD)).fetchSemanticsNodes().size)
    }

    @Test
    fun suggestions_are_offered_above_the_rules_and_can_be_skipped() {
        val s = SuggestedRule("s1", "Block Foo in the background", "12 times in 7 days", FilterRule.Action.DENY,
            RuleSuggestions.Candidate(RuleSuggestions.Kind.BACKGROUND, "com.foo", 12))
        var skipped: SuggestedRule? = null
        var accepted: SuggestedRule? = null
        show(loaded(block).copy(suggestions = listOf(s)), RulesActions(onSkipSuggestion = { skipped = it }, onAcceptSuggestion = { accepted = it }))
        rule.onNodeWithText("Block Foo in the background").assertIsDisplayed()
        rule.onNodeWithText("Skip").performClick()
        assertEquals(s, skipped)
        assertNull(accepted)
        rule.onNodeWithText("Add").performClick()
        assertEquals(s, accepted)
    }

    @Test
    fun every_rule_is_reachable_in_a_long_list() {
        val many = (1..40).map { block.copy(id = "id$it", label = "Block s$it.example.com", matchDomain = "s$it.example.com") }
        show(loaded(*many.toTypedArray()))
        rule.onNodeWithTag(TAG_RULES_LIST).performScrollToNode(hasTestTag(TAG_RULE_ROW + "id40"))
        rule.onNodeWithTag(TAG_RULE_ROW + "id40").assertIsDisplayed()
    }
}
