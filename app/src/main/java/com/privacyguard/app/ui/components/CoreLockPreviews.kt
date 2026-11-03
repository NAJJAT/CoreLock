package com.privacyguard.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme

/**
 * The four previews every 1.1 component must have (DESIGN_SPEC_1.1.md §3): light, dark,
 * Arabic (right to left) and 200 % font. Pair it with [PreviewSurface], which follows
 * the preview's night mode.
 */
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360)
@Preview(name = "Arabic RTL", locale = "ar", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 360)
@Preview(name = "Font 200 %", fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 360)
annotation class CoreLockPreviews

@Composable
internal fun PreviewSurface(content: @Composable () -> Unit) {
    CoreLockTheme(Appearance.SYSTEM) {
        Box(
            Modifier
                .background(CoreLockTheme.colors.background)
                .padding(CoreLockTheme.spacing.l),
        ) { content() }
    }
}
