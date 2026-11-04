package com.privacyguard.ui.flavor

import androidx.compose.runtime.Composable
import com.privacyguard.ui.mitm.MitmScreen

/** Advanced › HTTPS inspection. [onBack] is null while the screen is still a tab (until Phase 8). */
object PayloadInspectorUi {
    @Composable
    fun Screen(onBack: (() -> Unit)? = null) = MitmScreen(onBack)
}
