package com.privacyguard.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.privacyguard.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * On the phone, `uiautomator dump` showed the tab labels with zero-size bounds and no
 * click action. What TalkBack needs is one focusable node per tab, with a size, role
 * Tab, its selected state, a click action and the label read once.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class BottomBarAccessibilityTest {

    @get:Rule
    val rule = createComposeRule()

    private val tabs = listOf(
        NavTab("dashboard", R.string.nav_home, Icons.Default.Home),
        NavTab("apps", R.string.nav_apps, Icons.Default.Apps),
        NavTab("settings", R.string.nav_settings, Icons.Default.Settings),
    )

    private fun show(badge: Int = 0) {
        rule.setContent {
            var selected by mutableStateOf("dashboard")
            PgBottomBar(tabs, isSelected = { it.route == selected }, settingsBadge = badge, onSelect = { selected = it.route })
        }
    }

    @Test
    fun every_tab_is_one_clickable_tab_node_with_a_size() {
        show()
        for (label in listOf("Home", "Apps", "Settings")) {
            val node = rule.onNodeWithText(label).fetchSemanticsNode()
            assertEquals(label, Role.Tab, node.config.getOrNull(SemanticsProperties.Role))
            assertTrue("$label has no click action", node.config.contains(SemanticsActions.OnClick))
            assertTrue("$label is not one TalkBack stop", node.config.isMergingSemanticsOfDescendants)
            val size = node.boundsInRoot
            assertTrue("$label is ${size.width} x ${size.height}", size.width > 0f && size.height > 0f)
        }
    }

    @Test
    fun the_label_is_read_once() {
        show()
        // The merged node holds the label once, with no description added on top.
        rule.onAllNodesWithText("Home").assertCountEquals(1)
        val node = rule.onNodeWithText("Home").fetchSemanticsNode()
        assertEquals(null, node.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(listOf("Home"), node.config.getOrNull(SemanticsProperties.Text)?.map { it.text })
    }

    @Test
    fun selecting_a_tab_moves_the_selected_state() {
        show()
        rule.onNodeWithText("Home").assertIsSelected()
        rule.onNodeWithText("Apps").assertIsNotSelected().performClick()
        rule.onNodeWithText("Apps").assertIsSelected()
        rule.onNodeWithText("Home").assertIsNotSelected()
    }

    @Test
    fun the_badge_says_what_it_counts() {
        show(badge = 3)
        rule.onNodeWithText("Settings").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("3 threat alerts"))
        )
        // The bare number is not read on top of that.
        val node = rule.onNodeWithText("Settings").fetchSemanticsNode()
        assertEquals(listOf("Settings"), node.config.getOrNull(SemanticsProperties.Text)?.map { it.text })
    }
}
