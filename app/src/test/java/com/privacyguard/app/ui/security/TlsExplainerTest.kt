package com.privacyguard.app.ui.security

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.privacyguard.app.R
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** The TLS-fingerprint explanation of 1.0 Settings lives in Security analysis' TLS section (owner, 2026-11-04). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TlsExplainerTest {

    @get:Rule
    val rule = createComposeRule()

    private val explainer = ApplicationProvider.getApplicationContext<android.content.Context>()
        .getString(R.string.tls_fp_settings_explainer)

    @Test
    fun the_explainer_is_one_tap_away_even_before_anything_was_learned() {
        rule.setContent { CoreLockTheme(Appearance.DARK) { TlsBaselinePanel(TlsBaselineSummary(), onTrust = {}) } }
        rule.onNodeWithText(explainer).assertDoesNotExist()
        rule.onNodeWithTag(TAG_TLS_WHAT_IS).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(explainer).assertExists()
    }
}
