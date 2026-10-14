package com.privacyguard

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.privacyguard.app.core.security.AppSecurityMonitor
import com.privacyguard.app.core.security.SecurityRiskLevel
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.ui.SecureScreens

/**
 * Base activity for all PrivacyGuard activities. AppCompatActivity so the per-app
 * language (AppCompatDelegate.setApplicationLocales) applies on Android 12 and older.
 */
open class BaseActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val posture = AppSecurityMonitor.refresh(this)
        SecureScreens.deviceForcesSecure = posture.riskLevel != SecurityRiskLevel.LOW
        SecureScreens.debugBuild = com.privacyguard.app.BuildConfig.DEBUG
        SecureScreens.userAllowsScreenshots = SettingsPreferences.getInstance(this).allowScreenshots.value
        // Secure from the first frame; AppNavHost relaxes it only for NOT_SENSITIVE routes.
        SecureScreens.apply(window, route = null)
    }
}
