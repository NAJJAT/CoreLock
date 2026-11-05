package com.privacyguard.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PrivacyGuardColors = darkColorScheme(
    primary = LegacyDark.accent,
    onPrimary = LegacyDark.background,
    primaryContainer = LegacyDark.panelRaised,
    onPrimaryContainer = LegacyDark.text,
    secondary = LegacyDark.info,
    onSecondary = LegacyDark.background,
    secondaryContainer = LegacyDark.panelMuted,
    onSecondaryContainer = LegacyDark.text,
    tertiary = LegacyDark.warning,
    onTertiary = LegacyDark.background,
    tertiaryContainer = LegacyDark.warningDim,
    onTertiaryContainer = LegacyDark.warning,
    background = LegacyDark.background,
    onBackground = LegacyDark.text,
    surface = LegacyDark.panel,
    onSurface = LegacyDark.text,
    surfaceVariant = LegacyDark.panelRaised,
    onSurfaceVariant = LegacyDark.textMuted,
    outline = LegacyDark.borderStrong,
    outlineVariant = LegacyDark.border,
    error = LegacyDark.danger,
    onError = LegacyDark.background
)

@Composable
fun PrivacyGuardTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PrivacyGuardColors,
        typography = Typography,
        content = content
    )
}
