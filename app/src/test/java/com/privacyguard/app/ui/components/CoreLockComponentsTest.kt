package com.privacyguard.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The 1.1 components meet the accessibility rules of DESIGN_SPEC_1.1.md §3 on their own,
 * so every screen built from them inherits it: one control per row, the right role,
 * a spoken label, and 48 dp touch targets, also at 200 % font.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
// Real text measurement: the legacy mode measures text too small for size and wrap checks.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreLockComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun hero_is_one_switch_labelled_protection_that_toggles() {
        var toggled: Boolean? = null
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                StatusHeroCard(ProtectionState.ON, "You're protected", "Blocking ads and trackers · Standard", { toggled = it })
            }
        }
        rule.onNodeWithContentDescription("Protection", substring = true)
            .assert(hasRole(Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performClick()
        assertEquals(false, toggled)
        // The visual switch has no click of its own: exactly one switch node.
        assertEquals(1, rule.onAllNodesHasRole(Role.Switch))
    }

    @Test
    fun hero_cannot_be_toggled_while_starting() {
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                StatusHeroCard(ProtectionState.STARTING, "Starting…", "This takes a few seconds.", {})
            }
        }
        rule.onNodeWithContentDescription("Protection", substring = true).assertIsNotEnabled()
    }

    @Test
    fun toggle_row_is_one_switch_for_the_whole_row() {
        var checked by mutableStateOf(false)
        rule.setContent {
            CoreLockTheme(Appearance.LIGHT) {
                PrimaryToggleRow("Restart after a crash", checked, { checked = it }, subtitle = "Turn protection back on by itself")
            }
        }
        rule.onNodeWithText("Turn protection back on by itself", useUnmergedTree = true).performClick()
        assertTrue(checked)
        assertEquals(1, rule.onAllNodesHasRole(Role.Switch))
    }

    @Test
    fun segmented_control_is_a_group_of_radio_buttons() {
        var selected by mutableStateOf("Allowed")
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                SegmentedControl(listOf("Allowed", "Blocked in background", "Blocked"), selected, { it }, { selected = it })
            }
        }
        assertEquals(3, rule.onAllNodesHasRole(Role.RadioButton))
        rule.onNodeWithText("Blocked").performClick()
        assertEquals("Blocked", selected)
        rule.onNodeWithText("Blocked").assertIsSelected()
    }

    @Test
    fun technical_details_start_collapsed_and_say_so() {
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                TechnicalDetails { TechnicalValue("IP", "192.0.2.1") }
            }
        }
        assertEquals(0, onAllNodesWithTextCount("192.0.2.1"))
        rule.onNodeWithText("Technical details")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        rule.onNodeWithText("192.0.2.1").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
    }

    @Test
    fun error_state_retries_and_banner_dismiss_is_labelled() {
        var retried = false
        var dismissed = false
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                Column {
                    ErrorState("Couldn't load activity.", onRetry = { retried = true })
                    AttentionBanner("Private DNS is on.", "Open settings", onAction = {}, onDismiss = { dismissed = true })
                }
            }
        }
        rule.onNodeWithText("Try again").performClick()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        assertTrue(retried && dismissed)
    }

    @Test
    fun loading_skeleton_is_read_once_as_loading() {
        rule.setContent { CoreLockTheme(Appearance.DARK) { LoadingSkeleton(rows = 3) } }
        rule.onNodeWithContentDescription("Loading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
    }

    /** Short labels sit side by side; a word too long for a third of the width stacks the segments. */
    @Test
    fun segmented_control_stacks_instead_of_breaking_words() {
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                Column {
                    Box(Modifier.width(360.dp)) { SegmentedControl(listOf("A", "B", "C"), "A", { it }, {}) }
                    Box(Modifier.width(240.dp)) {
                        SegmentedControl(listOf("Toegestaan", "Achtergrondgeblokkeerd", "Geblokkeerd"), "Toegestaan", { it }, {})
                    }
                }
            }
        }
        val rowTops = listOf("A", "B", "C").map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("short labels share one row", 1, rowTops.distinct().size)
        val stackTops = listOf("Toegestaan", "Achtergrondgeblokkeerd", "Geblokkeerd")
            .map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("long words stack, one segment per line", 3, stackTops.distinct().size)
    }

    /** Every tappable element of every component is at least 48 × 48 dp. */
    @Test fun every_touch_target_is_at_least_48dp() = assertTouchTargets(fontScale = 1f)

    /** The same at 200 % font: text grows, targets must never shrink below 48 dp. */
    @Test fun every_touch_target_is_at_least_48dp_at_double_font() = assertTouchTargets(fontScale = 2f)

    private fun assertTouchTargets(fontScale: Float) {
        var density = 1f
        rule.setContent {
            val base = LocalDensity.current
            density = base.density
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                CoreLockTheme(Appearance.DARK) { AllComponents() }
            }
        }
        val small = mutableListOf<String>()
        var count = 0
        fun walk(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsActions.OnClick) != null) {
                count++
                val w = node.size.width / density
                val h = node.size.height / density
                if (w < 47.5f || h < 47.5f) {
                    val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                        ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    small += "$label=${w}x${h}dp"
                }
            }
            node.children.forEach(::walk)
        }
        walk(rule.onRoot().fetchSemanticsNode())
        assertTrue("no tappable nodes found", count > 10)
        assertTrue("touch targets below 48 dp at font $fontScale: $small", small.isEmpty())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesHasRole(role: Role): Int =
        onAllNodes(hasRole(role)).fetchSemanticsNodes().size

    private fun onAllNodesWithTextCount(text: String): Int =
        rule.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}

@androidx.compose.runtime.Composable
private fun AllComponents() {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        StatusHeroCard(ProtectionState.ON, "You're protected", "Blocking ads and trackers · Standard", {})
        PrimaryToggleRow("Restart after a crash", true, {}, subtitle = "Turn protection back on by itself")
        SettingRow("Protection level", onClick = {}, subtitle = "Standard")
        SectionHeader("Protection")
        AppRow("Chrome", "Allowed", {}, riskWord = "Uses trackers", blockedToday = 3)
        ActivityRow("Google ads", "Blocked · Ads and trackers list", ActivityKind.BLOCKED, {})
        PrimaryButton("Allow everywhere", {})
        SecondaryButton("Keep blocked", {})
        DestructiveButton("Clear all data", {})
        SegmentedControl(listOf("Allowed", "Blocked in background", "Blocked"), "Allowed", { it }, {})
        ChipRow(listOf("All", "Uses trackers"), "All", { it }, {})
        EmptyState("Nothing blocked yet.", actionLabel = "Turn on", onAction = {})
        ErrorState("Couldn't load activity.", onRetry = {})
        AttentionBanner("Private DNS is on.", "Open settings", onAction = {}, onDismiss = {})
        TechnicalDetails { TechnicalValue("IP", "192.0.2.1") }
        // Inside this scrolling column: a sheet body that scrolls by itself would crash here.
        SheetBody("What CoreLock can and can't do") { InfoSheetContent("Body", onDismiss = {}) }
    }
}
