package com.privacyguard.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Every colour pair the design uses meets WCAG 2.2 AA (DESIGN_SPEC_1.1.md §2.1): text
 * 4.5:1, icons and borders 3:1. Recomputed from the tokens, so a future colour edit that
 * breaks contrast fails here instead of on a user's screen.
 */
class ContrastTokensTest {

    private fun channel(c: Float): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> if (la > lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun textPairs(c: CoreLockColors) = mapOf(
        "onSurface/background" to (c.onSurface to c.background),
        "onSurface/surface" to (c.onSurface to c.surface),
        "onSurface/surfaceVariant" to (c.onSurface to c.surfaceVariant),
        "onSurfaceMuted/background" to (c.onSurfaceMuted to c.background),
        "onSurfaceMuted/surface" to (c.onSurfaceMuted to c.surface),
        "onSurfaceMuted/surfaceVariant" to (c.onSurfaceMuted to c.surfaceVariant),
        "primary/background" to (c.primary to c.background),
        "primary/surface" to (c.primary to c.surface),
        "primary/surfaceVariant" to (c.primary to c.surfaceVariant),
        "onPrimary/primary" to (c.onPrimary to c.primary),
        "onProtectedContainer/protectedContainer" to (c.onProtectedContainer to c.protectedContainer),
        "protected/protectedContainer" to (c.protected to c.protectedContainer),
        "attention/surface" to (c.attention to c.surface),
        "attention/surfaceVariant" to (c.attention to c.surfaceVariant),
        "onAttentionContainer/attentionContainer" to (c.onAttentionContainer to c.attentionContainer),
        "danger/surface" to (c.danger to c.surface),
        "danger/surfaceVariant" to (c.danger to c.surfaceVariant),
        "onDangerContainer/dangerContainer" to (c.onDangerContainer to c.dangerContainer),
        // The hero's icon and switch sit on the coloured containers.
        "attention/attentionContainer" to (c.attention to c.attentionContainer),
        "danger/dangerContainer" to (c.danger to c.dangerContainer),
    )

    private fun nonTextPairs(c: CoreLockColors) = mapOf(
        "outline/background" to (c.outline to c.background),
        "outline/surface" to (c.outline to c.surface),
        "outline/surfaceVariant" to (c.outline to c.surfaceVariant),
    )

    private fun check(name: String, colors: CoreLockColors) {
        val failures = textPairs(colors).mapNotNull { (pair, ab) ->
            ratio(ab.first, ab.second).takeIf { it < 4.5 }?.let { "$pair=%.2f".format(it) }
        } + nonTextPairs(colors).mapNotNull { (pair, ab) ->
            ratio(ab.first, ab.second).takeIf { it < 3.0 }?.let { "$pair=%.2f".format(it) }
        }
        assertTrue("$name theme below WCAG AA: $failures", failures.isEmpty())
    }

    @Test fun dark_tokens_meet_wcag_aa() = check("dark", CoreLockDarkColors)

    @Test fun light_tokens_meet_wcag_aa() = check("light", CoreLockLightColors)

    /** The spec quotes these numbers; if a token changes, the spec table must change too. */
    @Test fun ratios_match_the_spec() {
        assertEquals(14.71, ratio(CoreLockDarkColors.onSurface, CoreLockDarkColors.surface), 0.01)
        assertEquals(4.57, ratio(CoreLockLightColors.protected, CoreLockLightColors.protectedContainer), 0.01)
        assertEquals(3.32, ratio(CoreLockLightColors.outline, CoreLockLightColors.surfaceVariant), 0.01)
    }

    /** Sanity check of the formula itself: black on white is 21:1. */
    @Test fun formula_gives_21_for_black_on_white() {
        assertEquals(21.0, ratio(Color(0xFF000000), Color(0xFFFFFFFF)), 0.001)
    }
}
