package com.privacyguard.app.ui.home

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.privacyguard.app.ui.components.ProtectionState
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.vpn.interception.InspectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** DESIGN_SPEC_1.1.md §9 Phase 3: tap counts and Home's loading, empty and error states. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val on = HomeUiState(protection = ProtectionState.ON, level = "STANDARD", blockedToday = BlockedToday.Count(128))

    private fun show(
        state: HomeUiState,
        actions: HomeActions = HomeActions(),
        inspection: InspectionStatus = InspectionStatus.OFF,
    ) = rule.setContent {
        CoreLockTheme(Appearance.DARK) { HomeContent(state, inspection, actions) }
    }

    @Test
    fun something_not_working_is_one_tap_from_home() {
        var opened = false
        show(on, HomeActions(onOpenFix = { opened = true }))
        rule.onNodeWithTag(TAG_HOME_FIX).performClick()
        assertTrue(opened)
    }

    @Test
    fun a_pause_shows_when_protection_comes_back() {
        val until = System.currentTimeMillis() + 300_000
        show(on.copy(protection = ProtectionState.PAUSED, pausedUntil = until))
        rule.onNodeWithText("Protection is paused").assertExists()
        val at = android.text.format.DateFormat.getTimeFormat(org.robolectric.RuntimeEnvironment.getApplication()).format(java.util.Date(until))
        rule.onNodeWithText("Back on at $at.").assertExists()
    }

    @Test
    fun protected_status_needs_zero_taps() {
        show(on)
        rule.onNodeWithText("You're protected").assertExists()
        rule.onNodeWithText("Blocking ads and trackers · Standard").assertExists()
        // Merged into the row: TalkBack reads "Blocked today, 128" as one stop.
        rule.onNodeWithTag(TAG_BLOCKED_ROW).assert(androidx.compose.ui.test.hasText("128"))
    }

    @Test
    fun turning_protection_off_is_one_tap() {
        val toggles = mutableListOf<Boolean>()
        show(on, HomeActions(onToggle = { toggles += it }))
        rule.onNodeWithTag(TAG_HERO)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performClick()
        assertEquals(listOf(false), toggles)
    }

    @Test
    fun stopped_unexpectedly_turns_on_with_one_tap() {
        val toggles = mutableListOf<Boolean>()
        show(on.copy(protection = ProtectionState.STOPPED_UNEXPECTEDLY), HomeActions(onToggle = { toggles += it }))
        rule.onNodeWithText("Protection stopped").assertExists()
        rule.onNodeWithText("It stopped without you turning it off. Turn it on again.").assertExists()
        rule.onNodeWithTag(TAG_HERO).performClick()
        assertEquals(listOf(true), toggles)
    }

    @Test
    fun another_vpn_taking_over_says_so_in_plain_words() {
        show(on.copy(protection = ProtectionState.STOPPED_UNEXPECTEDLY, stopReason = com.privacyguard.app.vpn.UnexpectedStop.Reason.OTHER_VPN))
        rule.onNodeWithText("Protection stopped").assertExists()
        rule.onNodeWithText("Another VPN app took over. Turn CoreLock on to block again.").assertExists()
    }

    @Test
    fun changing_the_level_is_two_taps_and_closes_the_sheet() {
        val picked = mutableListOf<String>()
        show(on, HomeActions(onSelectLevel = { picked += it }))
        rule.onNodeWithTag(TAG_LEVEL_ROW).performClick()                 // tap 1
        rule.onNodeWithTag("${TAG_LEVEL_PREFIX}STANDARD")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        rule.onNodeWithText("Blocks more. Some apps or sites may break.").assertExists()
        rule.onNodeWithTag("${TAG_LEVEL_PREFIX}STRICT").performClick()   // tap 2
        assertEquals(listOf("STRICT"), picked)
        rule.waitForIdle()
        rule.onNodeWithTag("${TAG_LEVEL_PREFIX}MINIMAL").assertDoesNotExist()
    }

    @Test
    fun blocked_today_opens_the_list() {
        var opened = 0
        show(on, HomeActions(onOpenBlocked = { opened++ }))
        rule.onNodeWithTag(TAG_BLOCKED_ROW).assert(hasClickAction()).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun zero_while_off_has_nothing_to_open() {
        show(on.copy(protection = ProtectionState.OFF, blockedToday = BlockedToday.Count(0)))
        rule.onNodeWithTag(TAG_BLOCKED_ROW).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun loading_count_is_a_placeholder_not_a_zero() {
        show(on.copy(blockedToday = BlockedToday.Loading))
        rule.onNodeWithContentDescription("Loading").assertExists()
        rule.onNodeWithTag(TAG_BLOCKED_COUNT, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_BLOCKED_ROW).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun failed_count_says_so_and_retries() {
        var retries = 0
        show(on.copy(blockedToday = BlockedToday.Error), HomeActions(onRetryBlocked = { retries++ }))
        rule.onNodeWithText("Couldn't load").assertExists()
        rule.onNodeWithContentDescription("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun real_problems_cannot_be_dismissed_optional_ones_can() {
        show(on.copy(attention = HomeLogic.Attention.PrivateDns("dns.example")))
        rule.onNodeWithTag(TAG_ATTENTION).performScrollTo()
        rule.onNodeWithText("Open settings").assertExists()
        rule.onAllNodesWithText("Dismiss").assertCountEquals(0)
        rule.onNodeWithContentDescription("Dismiss").assertDoesNotExist()
    }

    @Test
    fun optional_battery_card_can_be_dismissed() {
        val dismissed = mutableListOf<HomeLogic.Attention>()
        show(on.copy(attention = HomeLogic.Attention.Battery(canDismiss = true)), HomeActions(onAttentionDismiss = { dismissed += it }))
        rule.onNodeWithContentDescription("Dismiss").performScrollTo().performClick()
        assertEquals(listOf<HomeLogic.Attention>(HomeLogic.Attention.Battery(canDismiss = true)), dismissed)
    }

    @Test
    fun https_inspection_is_never_silent_on_home() {
        show(on, inspection = InspectionStatus.ON)
        rule.onNodeWithText("HTTPS inspection is on").assertExists()
    }
}
