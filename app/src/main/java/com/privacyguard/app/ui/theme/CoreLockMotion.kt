package com.privacyguard.app.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Motion (DESIGN_SPEC_1.1.md §2.6): 150 ms small changes, 200 ms content, 300 ms sheets.
 * Every UI animation takes its spec from here, so Android's "Remove animations" turns
 * all of them off at once instead of screen by screen. No looping animations.
 */
object CoreLockMotion {
    const val SHORT_MS = 150
    const val MEDIUM_MS = 200
    const val LONG_MS = 300

    /** True when the user turned animations off (either scale set to 0). */
    fun animationsDisabled(context: Context): Boolean {
        val resolver = context.contentResolver
        val animator = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val transition = Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        return animator == 0f || transition == 0f
    }
}

/**
 * The tween for [durationMs], or an instant snap when animations are off. Read once per
 * composition entry: the setting rarely changes, and re-reading it per frame would be a
 * settings lookup on every recomposition.
 */
@Composable
fun <T> rememberCoreLockTween(durationMs: Int = CoreLockMotion.MEDIUM_MS): FiniteAnimationSpec<T> {
    val context = LocalContext.current
    return remember(context, durationMs) {
        if (CoreLockMotion.animationsDisabled(context)) snap() else tween(durationMs)
    }
}
