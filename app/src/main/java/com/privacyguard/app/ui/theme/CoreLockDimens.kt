package com.privacyguard.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The 4 dp grid (DESIGN_SPEC_1.1.md §2.3): only these values are used for spacing. */
@Immutable
object CoreLockSpacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 48.dp

    /** Screen side padding. */
    val screen: Dp = l

    /** WCAG 2.2 / Android minimum touch target for everything tappable. */
    val minTouchTarget: Dp = 48.dp
}

/** Corner radii (§2.4): 12 small, 16 cards, 28 sheets. */
@Immutable
object CoreLockShapes {
    val small = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

/** The same radii for Material components (buttons, chips, dialogs, sheets). */
internal val CoreLockMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
