package com.privacyguard.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colour tokens of the 1.1 design system (docs/ux/DESIGN_SPEC_1.1.md §2.1).
 * Screens use these names, never a hex value; ContrastTokensTest keeps every text
 * pair at 4.5:1 or more and every non-text pair at 3:1 or more.
 *
 * Teal means protected, amber needs attention, red is real danger only (malware,
 * protection stopped unexpectedly, destructive actions).
 */
@Immutable
data class CoreLockColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val primary: Color,
    val onPrimary: Color,
    val protected: Color,
    val protectedContainer: Color,
    val onProtectedContainer: Color,
    val attention: Color,
    val attentionContainer: Color,
    val onAttentionContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    /** Borders, dividers and focus rings. Non-text only: light outline is 3.3–3.8:1. */
    val outline: Color,
)

val CoreLockDarkColors = CoreLockColors(
    isDark = true,
    background = Color(0xFF0E1320),
    surface = Color(0xFF161D2E),
    surfaceVariant = Color(0xFF1F2840),
    onSurface = Color(0xFFECF0F7),
    onSurfaceMuted = Color(0xFFA9B3C6),
    primary = Color(0xFF3DDBB4),
    onPrimary = Color(0xFF04211A),
    protected = Color(0xFF3DDBB4),
    protectedContainer = Color(0xFF0F3A33),
    onProtectedContainer = Color(0xFFA7F3DD),
    attention = Color(0xFFF2B85B),
    attentionContainer = Color(0xFF3D2E0F),
    onAttentionContainer = Color(0xFFFFE1A8),
    danger = Color(0xFFFF8A8A),
    dangerContainer = Color(0xFF4A1A1F),
    onDangerContainer = Color(0xFFFFD6D6),
    outline = Color(0xFF8390A8),
)

val CoreLockLightColors = CoreLockColors(
    isDark = false,
    background = Color(0xFFF6F8FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF1F6),
    onSurface = Color(0xFF111827),
    onSurfaceMuted = Color(0xFF4B5567),
    primary = Color(0xFF00796B),
    onPrimary = Color(0xFFFFFFFF),
    protected = Color(0xFF00796B),
    protectedContainer = Color(0xFFD3F5EC),
    onProtectedContainer = Color(0xFF00382F),
    attention = Color(0xFF8A5A00),
    attentionContainer = Color(0xFFFCEBC7),
    onAttentionContainer = Color(0xFF4A3000),
    danger = Color(0xFFB3261E),
    dangerContainer = Color(0xFFFBDCD9),
    onDangerContainer = Color(0xFF5C0F0B),
    outline = Color(0xFF79849A),
)

val LocalCoreLockColors = staticCompositionLocalOf { CoreLockDarkColors }
