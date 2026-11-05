package com.privacyguard.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The 1.0 colour names follow the theme since 1.1 (Color.kt). Before, they were dark-only
 * and the 1.0 screens drew near-white text on the light background. This keeps every text
 * colour of both palettes readable (WCAG AA, 4.5:1) on every panel it is drawn on.
 *
 * Dark keeps the 1.0 values; only text, textMuted and textFaint are checked there (the
 * status colours were tested on device in 1.0 and are unchanged). Light is new, so its
 * status colours are checked too.
 */
class LegacyPaletteContrastTest {

    private fun channel(c: Float): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun panels(p: LegacyPalette) = mapOf(
        "background" to p.background, "backgroundAlt" to p.backgroundAlt, "panel" to p.panel,
        "panelRaised" to p.panelRaised, "panelMuted" to p.panelMuted, "panelStrong" to p.panelStrong,
    )

    private fun failures(p: LegacyPalette, texts: Map<String, Color>): List<String> =
        texts.flatMap { (t, fg) ->
            panels(p).mapNotNull { (b, bg) -> ratio(fg, bg).takeIf { it < 4.5 }?.let { "$t/$b=%.2f".format(it) } }
        }

    @Test
    fun dark_text_colours_are_readable_on_every_panel() {
        val p = LegacyDark
        val bad = failures(p, mapOf("text" to p.text, "textMuted" to p.textMuted, "textFaint" to p.textFaint))
        assertTrue("Below 4.5:1: $bad", bad.isEmpty())
    }

    @Test
    fun light_text_and_status_colours_are_readable_on_every_panel() {
        val p = LegacyLight
        val bad = failures(
            p,
            mapOf(
                "text" to p.text, "textMuted" to p.textMuted, "textFaint" to p.textFaint,
                "accent" to p.accent, "danger" to p.danger, "warning" to p.warning, "info" to p.info,
            ),
        )
        assertTrue("Below 4.5:1: $bad", bad.isEmpty())
    }

    @Test
    fun light_text_is_dark_and_dark_text_is_light() {
        // The bug this replaces: a single dark-only palette in both themes.
        assertTrue(luminance(LegacyLight.text) < luminance(LegacyLight.background))
        assertTrue(luminance(LegacyDark.text) > luminance(LegacyDark.background))
    }
}
