package com.privacyguard.ui.flavor

import androidx.compose.runtime.Composable
import com.privacyguard.ui.mitm.MitmScreen

/** Settings › Advanced › HTTPS inspection. */
object PayloadInspectorUi {
    @Composable
    fun Screen(onBack: (() -> Unit)? = null) = MitmScreen(onBack)
}
