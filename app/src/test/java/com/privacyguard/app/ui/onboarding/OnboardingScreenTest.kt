package com.privacyguard.app.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.privacyguard.app.R
import com.privacyguard.app.ui.home.TAG_LEVEL_PREFIX
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

/** DESIGN_SPEC_1.1.md §6.7 and §9: at most three screens, the limits said up front. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)

    private var vpnAsked = 0
    private var finished: String? = null

    private fun show(batteryRestricted: Boolean = false) = rule.setContent {
        var step by androidx.compose.runtime.remember { mutableStateOf(OnboardingStep.WHAT) }
        CoreLockTheme(Appearance.LIGHT) {
            OnboardingContent(
                step = step,
                initialLevel = "STANDARD",
                batteryRestricted = batteryRestricted,
                onStep = { step = it },
                onContinueToVpn = { vpnAsked++; step = OnboardingStep.LEVEL },
                onOpenBattery = {},
                onFinish = { finished = it },
            )
        }
    }

    @Test
    fun never_more_than_three_screens() {
        assertTrue(OnboardingStep.entries.size <= 3)
    }

    @Test
    fun first_screen_says_what_it_does_and_what_it_cant() {
        show()
        rule.onNodeWithText(text(R.string.onb11_what_body)).assertExists()
        rule.onNodeWithText(text(R.string.onb11_what_limit)).assertExists()
        rule.onNodeWithText(text(R.string.onb11_step).format(1, 3)).assertExists()
    }

    @Test
    fun three_taps_from_start_to_home_with_the_vpn_asked_once() {
        show()
        rule.onNodeWithTag(TAG_ONB_NEXT).performClick()
        rule.onNodeWithText(text(R.string.onb11_vpn_body)).assertExists()
        rule.onNodeWithTag(TAG_ONB_CONTINUE).performClick()
        assertEquals(1, vpnAsked)
        // Standard is chosen unless the user picks another level.
        rule.onNodeWithTag("${TAG_LEVEL_PREFIX}STANDARD").assertIsSelected()
        rule.onNodeWithTag(TAG_ONB_DONE).performClick()
        assertEquals("STANDARD", finished)
    }

    @Test
    fun the_chosen_level_is_the_one_stored() {
        show()
        rule.onNodeWithTag(TAG_ONB_NEXT).performClick()
        rule.onNodeWithTag(TAG_ONB_CONTINUE).performClick()
        rule.onNodeWithTag("${TAG_LEVEL_PREFIX}STRICT").performScrollTo().performClick()
        rule.onNodeWithTag(TAG_ONB_DONE).performClick()
        assertEquals("STRICT", finished)
    }

    @Test
    fun what_leaves_the_phone_is_one_tap_away_before_the_vpn_is_asked() {
        show()
        rule.onNodeWithTag(TAG_ONB_NEXT).performClick()
        rule.onNodeWithTag(TAG_ONB_LEAVES).performScrollTo().performClick()
        rule.waitForIdle()
        // The sheet shows the full disclosure (every host, OutboundHostsDisclosureTest).
        rule.onNodeWithText(text(R.string.onb_c_net_body), substring = true).assertExists()
        assertEquals(0, vpnAsked)
    }

    @Test
    fun battery_advice_only_when_android_restricts_the_app() {
        show(batteryRestricted = false)
        rule.onNodeWithTag(TAG_ONB_NEXT).performClick()
        rule.onNodeWithTag(TAG_ONB_CONTINUE).performClick()
        rule.onNodeWithTag(TAG_ONB_BATTERY).assertDoesNotExist()
    }

    @Test
    fun battery_advice_shows_when_restricted() {
        show(batteryRestricted = true)
        rule.onNodeWithTag(TAG_ONB_NEXT).performClick()
        rule.onNodeWithTag(TAG_ONB_CONTINUE).performClick()
        rule.onNodeWithTag(TAG_ONB_BATTERY).performScrollTo().assertExists()
    }
}
