package com.privacyguard.app.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Phase 3 screenshot item 3: the action label starts where the message starts. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttentionBannerTest {

    @get:Rule
    val rule = createComposeRule()

    private val message = "Private DNS is on. Websites and apps may not load until you turn it off."
    private val action = "Open settings"

    private fun show(direction: LayoutDirection, dismissible: Boolean) = rule.setContent {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            CoreLockTheme(Appearance.DARK) {
                AttentionBanner(
                    message, action, onAction = {}, modifier = Modifier.width(379.dp),
                    onDismiss = if (dismissible) ({}) else null,
                )
            }
        }
    }

    private fun bounds(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()

    @Test
    fun the_action_lines_up_with_the_message_left_to_right() {
        show(LayoutDirection.Ltr, dismissible = true)
        assertEquals(bounds(message).left.value, bounds(action).left.value, 0.5f)
    }

    @Test
    fun the_action_lines_up_with_the_message_right_to_left() {
        show(LayoutDirection.Rtl, dismissible = false)
        assertEquals(bounds(message).right.value, bounds(action).right.value, 0.5f)
    }
}
