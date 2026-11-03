package com.privacyguard.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Settings › Appearance (owner's decision D4: default [SYSTEM]). */
enum class Appearance { SYSTEM, LIGHT, DARK }

/**
 * The 1.1 theme. Material components get a colour scheme built from the same tokens,
 * so a stock Button or Switch already matches; custom components read
 * [CoreLockTheme.colors] for the tokens Material has no slot for (protected, attention).
 */
@Composable
fun CoreLockTheme(
    appearance: Appearance = Appearance.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val colors = if (dark) CoreLockDarkColors else CoreLockLightColors
    CompositionLocalProvider(LocalCoreLockColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = CoreLockTypography,
            shapes = CoreLockMaterialShapes,
            content = content,
        )
    }
}

object CoreLockTheme {
    val colors: CoreLockColors
        @Composable @ReadOnlyComposable get() = LocalCoreLockColors.current
    val spacing get() = CoreLockSpacing
    val shapes get() = CoreLockShapes
}

private fun CoreLockColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = protectedContainer,
        onPrimaryContainer = onProtectedContainer,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = surfaceVariant,
        onSecondaryContainer = onSurface,
        tertiary = attention,
        onTertiary = background,
        tertiaryContainer = attentionContainer,
        onTertiaryContainer = onAttentionContainer,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceMuted,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surfaceVariant,
        outline = outline,
        outlineVariant = surfaceVariant,
        error = danger,
        onError = background,
        errorContainer = dangerContainer,
        onErrorContainer = onDangerContainer,
    )
}
