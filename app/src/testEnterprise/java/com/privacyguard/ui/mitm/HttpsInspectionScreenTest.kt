package com.privacyguard.ui.mitm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.privacyguard.app.R
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
 * Advanced › HTTPS inspection (DESIGN_SPEC_1.1.md §5.7) and decision D5: switching
 * inspection on always asks first, and only the answer turns it on.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HttpsInspectionScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private class Calls { var confirmed = 0; var consented = 0; var off = 0 }

    /** The real switch; "enabled" follows what the callbacks would do in the ViewModel. */
    private fun show(enabled: Boolean, consentValid: Boolean): Calls {
        val calls = Calls()
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                var on by remember { mutableStateOf(enabled) }
                InspectionSwitch(
                    isEnabled = on,
                    consentValid = consentValid,
                    onConfirmed = { calls.confirmed++; on = true },
                    onConsented = { calls.consented++; on = true },
                    onSwitchedOff = { calls.off++; on = false },
                )
            }
        }
        return calls
    }

    @Test
    fun the_switch_asks_for_consent_without_it_and_confirms_with_it() {
        assertEquals(SwitchOnPrompt.CONSENT, switchOnPrompt(consentValid = false))
        assertEquals(SwitchOnPrompt.CONFIRM, switchOnPrompt(consentValid = true))
    }

    @Test
    fun switching_on_with_consent_asks_first_and_cancel_leaves_it_off() {
        val calls = show(enabled = false, consentValid = true)
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).performClick()

        rule.onNodeWithTag(TAG_HTTPS_CONFIRM).assertExists()
        rule.onNodeWithText(str(R.string.ui_https_confirm_title)).assertExists()
        assertEquals("nothing is turned on before the answer", 0, calls.confirmed)
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).assertIsOff()

        rule.onNodeWithText(str(R.string.mitm_cancel)).performClick()
        rule.onNodeWithTag(TAG_HTTPS_CONFIRM).assertDoesNotExist()
        assertEquals(0, calls.confirmed)
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).assertIsOff()
    }

    @Test
    fun confirming_turns_it_on_once() {
        val calls = show(enabled = false, consentValid = true)
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).performClick()
        rule.onNodeWithText(str(R.string.ui_https_confirm_on)).performClick()

        assertEquals(1, calls.confirmed)
        assertEquals("consent is not recorded again by the confirmation", 0, calls.consented)
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).assertIsOn()
    }

    @Test
    fun without_consent_the_unchanged_consent_dialog_is_the_one_question() {
        val calls = show(enabled = false, consentValid = false)
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).performClick()

        rule.onNodeWithTag(TAG_HTTPS_CONSENT).assertExists()
        rule.onNodeWithText(str(R.string.mitm_consent_title)).assertExists()
        rule.onNodeWithTag(TAG_HTTPS_CONFIRM).assertDoesNotExist()
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).assertIsOff()

        rule.onNodeWithText(str(R.string.mitm_consent_confirm)).performClick()
        assertEquals(1, calls.consented)
        assertEquals(0, calls.confirmed)
        rule.onNodeWithTag(TAG_HTTPS_CONFIRM).assertDoesNotExist()
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).assertIsOn()
    }

    @Test
    fun declining_consent_leaves_it_off() {
        val calls = show(enabled = false, consentValid = false)
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).performClick()
        rule.onNodeWithText(str(R.string.mitm_cancel)).performClick()

        assertEquals(0, calls.consented)
        assertEquals(0, calls.confirmed)
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).assertIsOff()
    }

    @Test
    fun switching_off_needs_no_question() {
        val calls = show(enabled = true, consentValid = true)
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).performClick()

        assertEquals(1, calls.off)
        rule.onNodeWithTag(TAG_HTTPS_CONFIRM).assertDoesNotExist()
        rule.onNodeWithTag(TAG_HTTPS_CONSENT).assertDoesNotExist()
        rule.onNodeWithTag(TAG_HTTPS_SWITCH).assertIsOff()
    }

    private val palette = JsonPalette(key = Color(1), string = Color(2), number = Color(3), literal = Color(4))

    @Test
    fun json_keys_values_and_literals_get_their_colours_and_the_text_is_unchanged() {
        val json = "{\n  \"a\": \"x\",\n  \"n\": -1.5,\n  \"t\": true,\n  \"z\": null\n}"
        val out = syntaxHighlight(json, palette)
        assertEquals(json, out.text)
        fun colourOf(token: String) = out.spanStyles.first { out.text.substring(it.start, it.end) == token }.item.color
        assertEquals(palette.key, colourOf("\"a\""))
        assertEquals(palette.string, colourOf("\"x\""))
        assertEquals(palette.number, colourOf("-1.5"))
        assertEquals(palette.literal, colourOf("true"))
        assertEquals(palette.literal, colourOf("null"))
    }

    @Test
    fun a_large_body_is_coloured_in_linear_time() {
        // 1.0 copied the rest of the text at every character: ~500 M copies for 32 KB.
        val big = "[" + (1..20_000).joinToString(",") { "{\"k$it\": $it}" } + "]"
        val start = System.nanoTime()
        val out = syntaxHighlight(big, palette)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(big, out.text)
        assertTrue("took $ms ms for ${big.length} chars", ms < 2_000)
    }

    @Test
    fun an_unterminated_string_does_not_crash() {
        assertEquals("{\"a", syntaxHighlight("{\"a", palette).text)
        assertEquals("\"", syntaxHighlight("\"", palette).text)
    }
}
