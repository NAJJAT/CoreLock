package com.privacyguard.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Colours and font sizes come from the theme only (DESIGN_SPEC_1.1.md §2.7): a hex
 * `Color(0x…)`, a named `Color.Red` or a `fontSize = 12.sp` in a screen bypasses the
 * tokens, so light mode, contrast and font scaling break one screen at a time.
 *
 * [LEGACY] lists the 1.0 files that still do this. The redesign phases rewrite them, and
 * each one must leave the list when it is clean (the second test fails otherwise), so
 * the list only shrinks. It is empty at the end of Phase 8.
 */
class ThemeTokenUsageTest {

    private val roots = listOf("src/main/java", "src/enterprise/java", "src/debug/java").map(::File)

    private val forbidden = listOf(
        Regex("""Color\(0x"""),
        Regex("""Color\.(Red|Green|Blue|Black|White|Yellow|Gray|Cyan|Magenta|DarkGray|LightGray)\b"""),
        Regex("""fontSize\s*=\s*[0-9.]+\.sp"""),
    )

    private val LEGACY = setOf(
        "src/main/java/com/privacyguard/app/ui/ads/AdsScreen.kt",
        "src/main/java/com/privacyguard/app/ui/components/NetworkMapCanvas.kt",
        "src/main/java/com/privacyguard/app/ui/components/PrivacyGuardUi.kt",
        "src/main/java/com/privacyguard/app/ui/components/TemporalHeatmap.kt",
        "src/main/java/com/privacyguard/app/ui/dashboard/DashboardScreen.kt",
        "src/main/java/com/privacyguard/app/ui/statistics/StatisticsScreen.kt",
    )

    /** UI files: everything under a `ui` package (and the debug catalog), except the theme itself. */
    private fun uiFiles(): List<File> = roots.flatMap { root ->
        root.walkTopDown().filter { f ->
            val path = f.invariantPath()
            f.isFile && f.extension == "kt" &&
                ("/ui/" in path || "/debug/" in path) &&
                "/ui/theme/" !in path
        }.toList()
    }

    private fun File.invariantPath() = path.replace('\\', '/')

    private fun offenders(): Set<String> = uiFiles()
        .filter { f -> val text = f.readText(); forbidden.any { it.containsMatchIn(text) } }
        .map { it.invariantPath() }
        .toSet()

    @Test
    fun ui_files_take_colours_and_font_sizes_from_the_theme() {
        assertTrue("no UI files found; wrong working directory?", uiFiles().isNotEmpty())
        val fresh = offenders() - LEGACY
        assertTrue(
            "Use CoreLockTheme.colors / MaterialTheme.typography instead of literals in: $fresh",
            fresh.isEmpty(),
        )
    }

    @Test
    fun legacy_list_only_shrinks() {
        val clean = LEGACY - offenders()
        assertTrue("These files are clean now; remove them from LEGACY: $clean", clean.isEmpty())
    }
}
