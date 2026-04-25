package com.privacyguard.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PrivacyGuardColors = darkColorScheme(
    primary = PgAccent,
    onPrimary = PgBackground,
    primaryContainer = PgPanelRaised,
    onPrimaryContainer = PgText,
    secondary = PgInfo,
    onSecondary = PgBackground,
    secondaryContainer = PgPanelMuted,
    onSecondaryContainer = PgText,
    tertiary = PgWarning,
    onTertiary = PgBackground,
    tertiaryContainer = PgWarningDim,
    onTertiaryContainer = PgWarning,
    background = PgBackground,
    onBackground = PgText,
    surface = PgPanel,
    onSurface = PgText,
    surfaceVariant = PgPanelRaised,
    onSurfaceVariant = PgTextMuted,
    outline = PgBorderStrong,
    outlineVariant = PgBorder,
    error = PgDanger,
    onError = PgBackground
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
