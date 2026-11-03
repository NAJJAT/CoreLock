package com.privacyguard.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Item 4 of the Phase 3 screenshots: the hero headline takes one or two lines, never three,
 * and never breaks inside a word, in every language at 100 % and 200 % font (Android 14+
 * scales large text less than 2x; Robolectric applies the same table on API 36). Fonts here
 * are Robolectric's, so the device screenshots stay the final check, Arabic most of all.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class StatusHeroHeadlineTest(private val lang: String, private val scale: Float) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}x")
        fun cases() = listOf("en", "ar", "fr", "nl").flatMap { l -> listOf(1f, 2f).map { arrayOf<Any>(l, it) } }
    }

    @get:Rule
    val rule = createComposeRule()

    private val titles = listOf(R.string.ui_hero_on, R.string.ui_hero_off, R.string.ui_hero_starting, R.string.ui_hero_stopped)

    @Test
    fun the_headline_takes_at_most_two_lines_and_never_splits_a_word() {
        RuntimeEnvironment.setQualifiers("+$lang")
        RuntimeEnvironment.setFontScale(scale)
        val res = RuntimeEnvironment.getApplication().resources
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                // Home: 411 dp phone minus the 16 dp screen padding on both sides.
                Column(Modifier.width(379.dp)) {
                    for (id in titles) StatusHeroCard(ProtectionState.ON, stringResource(id), "x", {})
                }
            }
        }
        for (id in titles) {
            val title = res.getString(id)
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.single()
            val lines = (0 until layout.lineCount).map { title.substring(layout.getLineStart(it), layout.getLineEnd(it)) }
            assertTrue("$lang ${scale}x '$title' takes ${layout.lineCount} lines: $lines", layout.lineCount <= 2)
            for (i in 1 until layout.lineCount) {
                val at = layout.getLineStart(i)
                assertTrue("$lang ${scale}x '$title' breaks inside a word: $lines", title[at - 1].isWhitespace())
            }
        }
    }
}
