package com.privacyguard.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 1.1 type scale (DESIGN_SPEC_1.1.md §2.2). The system font only (Roboto, and Noto Sans
 * Arabic for Arabic), no monospace and no letter spacing on labels: the old Monospace
 * labels with 1.2 sp tracking were hard to read and broke Arabic shaping.
 *
 * Line heights are in em, so they grow with the font scale and never clip tall glyphs
 * at 200 %. Nothing is smaller than 12 sp.
 */
val CoreLockTypography = Typography(
    headlineMedium = style(28, FontWeight.Medium, 1.3),
    titleLarge = style(22, FontWeight.Medium, 1.3),
    titleMedium = style(16, FontWeight.Medium, 1.4),
    titleSmall = style(14, FontWeight.Medium, 1.4),
    bodyLarge = style(16, FontWeight.Normal, 1.4),
    bodyMedium = style(14, FontWeight.Normal, 1.4),
    bodySmall = style(12, FontWeight.Normal, 1.4),
    labelLarge = style(14, FontWeight.Medium, 1.3),
    labelMedium = style(12, FontWeight.Medium, 1.3),
    labelSmall = style(12, FontWeight.Medium, 1.3),
)

/**
 * IP addresses, hashes and fingerprints inside Technical details only. The one place
 * monospace is allowed: digits line up and look-alike characters stay distinct.
 */
val CoreLockTechnicalValue = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 1.4.em,
)

private fun style(size: Int, weight: FontWeight, lineHeightEm: Double) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeightEm.em,
)
