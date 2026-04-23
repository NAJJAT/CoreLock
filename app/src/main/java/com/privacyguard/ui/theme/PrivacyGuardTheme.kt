package com.privacyguard.ui.theme

import androidx.compose.runtime.Composable
import com.privacyguard.app.ui.theme.PrivacyGuardTheme as AppPrivacyGuardTheme

@Composable
fun PrivacyGuardTheme(
    content: @Composable () -> Unit,
) {
    AppPrivacyGuardTheme(content = content)
}
