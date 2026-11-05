package com.privacyguard.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The 1.0 colour names (`Pg…`) that the screens not yet rebuilt on [CoreLockColors] still use
 * (Statistics, Security systems, Ads, Security analysis, App traffic, Domain detail, Alerts, …).
 *
 * Until 1.1 these were fixed dark values. With Settings › Appearance (D4) the app can be light,
 * and those screens drew near-white text on a light background. Each name now follows the
 * current theme: [LegacyDark] holds exactly the 1.0 values, so dark mode looks as before;
 * [LegacyLight] maps each role to the 1.1 light tokens. Reading one needs a composition, which
 * is where every screen already reads them.
 */
@Immutable
class LegacyPalette(
    val background: Color,
    val backgroundAlt: Color,
    val panel: Color,
    val panelRaised: Color,
    val panelMuted: Color,
    val panelStrong: Color,
    val border: Color,
    val borderStrong: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentDim: Color,
    val danger: Color,
    val dangerDim: Color,
    val warning: Color,
    val warningDim: Color,
    val info: Color,
    val infoDim: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val thumb: Color,
    val accentHalo: Color,
    val heroGradient: List<Color>,
    val adsGradient: List<Color>,
    val mapGrid: Color,
    val mapDot: Color,
    val mapDevice: Color,
)

val LegacyDark = LegacyPalette(
    background = Color(0xFF0A0D12),
    backgroundAlt = Color(0xFF111620),
    panel = Color(0xFF161C28),
    panelRaised = Color(0xFF1A2235),
    panelMuted = Color(0xFF212C40),
    panelStrong = Color(0xFF1E2638),
    border = Color(0x12FFFFFF),
    borderStrong = Color(0x21FFFFFF),
    accent = Color(0xFF00E5A0),
    accentStrong = Color(0xFF00B87A),
    accentDim = Color(0x1F00E5A0),
    danger = Color(0xFFFF4D6A),
    dangerDim = Color(0x1FFF4D6A),
    warning = Color(0xFFFFB23F),
    warningDim = Color(0x1FFFB23F),
    info = Color(0xFF4D9CFF),
    infoDim = Color(0x1F4D9CFF),
    text = Color(0xFFE8EDF5),
    textMuted = Color(0xFF8B95A8),
    // Was 0xFF5A6478 (2.4–3.3:1, below WCAG AA). 0xFF8993A8 reaches 4.5:1 on every panel
    // colour above, including panelMuted (UX audit 1.1, section 5).
    textFaint = Color(0xFF8993A8),
    thumb = Color(0xFFFFFFFF),
    accentHalo = Color(0x3300E5A0),
    heroGradient = listOf(Color(0xFF0F1E30), Color(0xFF0D2819)),
    adsGradient = listOf(Color(0x2200E5A0), Color(0x1200B1FF), Color(0x10111620)),
    mapGrid = Color(0x12FFFFFF),
    mapDot = Color(0x1AFFFFFF),
    mapDevice = Color(0xFFFFFFFF),
)

/** Same roles on the 1.1 light tokens ([CoreLockLightColors]); text pairs are 4.5:1 or more. */
val LegacyLight = LegacyPalette(
    background = Color(0xFFF6F8FB),
    backgroundAlt = Color(0xFFEDF1F6),
    panel = Color(0xFFFFFFFF),
    panelRaised = Color(0xFFFFFFFF),
    panelMuted = Color(0xFFEDF1F6),
    panelStrong = Color(0xFFF1F4F8),
    border = Color(0x1A111827),
    borderStrong = Color(0x2E111827),
    accent = Color(0xFF00796B),
    accentStrong = Color(0xFF005B50),
    accentDim = Color(0x1F00796B),
    danger = Color(0xFFB3261E),
    dangerDim = Color(0x1FB3261E),
    warning = Color(0xFF8A5A00),
    warningDim = Color(0x1F8A5A00),
    info = Color(0xFF1F5FBF),
    infoDim = Color(0x1F1F5FBF),
    text = Color(0xFF111827),
    textMuted = Color(0xFF4B5567),
    textFaint = Color(0xFF566173),
    thumb = Color(0xFFFFFFFF),
    accentHalo = Color(0x2600796B),
    heroGradient = listOf(Color(0xFFE4EEF9), Color(0xFFDDF3EA)),
    adsGradient = listOf(Color(0x2200796B), Color(0x141F5FBF), Color(0x10EDF1F6)),
    mapGrid = Color(0x14111827),
    mapDot = Color(0x24111827),
    mapDevice = Color(0xFF111827),
)

/** The palette for the current theme. */
val legacyColors: LegacyPalette
    @Composable @ReadOnlyComposable
    get() = if (LocalCoreLockColors.current.isDark) LegacyDark else LegacyLight

val PgBackground: Color @Composable @ReadOnlyComposable get() = legacyColors.background
val PgBackgroundAlt: Color @Composable @ReadOnlyComposable get() = legacyColors.backgroundAlt
val PgPanel: Color @Composable @ReadOnlyComposable get() = legacyColors.panel
val PgPanelRaised: Color @Composable @ReadOnlyComposable get() = legacyColors.panelRaised
val PgPanelMuted: Color @Composable @ReadOnlyComposable get() = legacyColors.panelMuted
val PgPanelStrong: Color @Composable @ReadOnlyComposable get() = legacyColors.panelStrong
val PgBorder: Color @Composable @ReadOnlyComposable get() = legacyColors.border
val PgBorderStrong: Color @Composable @ReadOnlyComposable get() = legacyColors.borderStrong

val PgAccent: Color @Composable @ReadOnlyComposable get() = legacyColors.accent
val PgAccentStrong: Color @Composable @ReadOnlyComposable get() = legacyColors.accentStrong
val PgAccentDim: Color @Composable @ReadOnlyComposable get() = legacyColors.accentDim
val PgDanger: Color @Composable @ReadOnlyComposable get() = legacyColors.danger
val PgDangerDim: Color @Composable @ReadOnlyComposable get() = legacyColors.dangerDim
val PgWarning: Color @Composable @ReadOnlyComposable get() = legacyColors.warning
val PgWarningDim: Color @Composable @ReadOnlyComposable get() = legacyColors.warningDim
val PgInfo: Color @Composable @ReadOnlyComposable get() = legacyColors.info
val PgInfoDim: Color @Composable @ReadOnlyComposable get() = legacyColors.infoDim

val PgText: Color @Composable @ReadOnlyComposable get() = legacyColors.text
val PgTextMuted: Color @Composable @ReadOnlyComposable get() = legacyColors.textMuted
val PgTextFaint: Color @Composable @ReadOnlyComposable get() = legacyColors.textFaint

/** Switch thumbs on the 1.0 toggles. */
val PgThumb: Color @Composable @ReadOnlyComposable get() = legacyColors.thumb
/** The soft circle behind the shield on Security systems. */
val PgAccentHalo: Color @Composable @ReadOnlyComposable get() = legacyColors.accentHalo
/** Security systems' top card. */
val PgHeroGradient: List<Color> @Composable @ReadOnlyComposable get() = legacyColors.heroGradient
/** The Ads screen's top card. */
val PgAdsGradient: List<Color> @Composable @ReadOnlyComposable get() = legacyColors.adsGradient
/** Network map: grid lines, background dots, and the phone at the centre. */
val PgMapGrid: Color @Composable @ReadOnlyComposable get() = legacyColors.mapGrid
val PgMapDot: Color @Composable @ReadOnlyComposable get() = legacyColors.mapDot
val PgMapDevice: Color @Composable @ReadOnlyComposable get() = legacyColors.mapDevice

/** Statistics' chart of organisations: one colour per slice, up to 12. Readable on both themes. */
val PgChartPalette = listOf(
    Color(0xFF00E5A0), Color(0xFF4D9CFF), Color(0xFFFFB23F),
    Color(0xFFFF4D6A), Color(0xFF9C6DFF), Color(0xFF00C8E0),
    Color(0xFFFF8C42), Color(0xFF6BFF6B), Color(0xFFFF6B9D),
    Color(0xFFFFD166), Color(0xFF06D6A0), Color(0xFFEF476F),
)
