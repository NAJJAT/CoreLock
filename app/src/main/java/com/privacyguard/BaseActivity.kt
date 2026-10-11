package com.privacyguard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.privacyguard.app.core.security.AppSecurityMonitor
import com.privacyguard.app.core.security.SecurityRiskLevel

/**
 * Base activity for all PrivacyGuard activities.
 */
open class BaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val posture = AppSecurityMonitor.refresh(this)
        if (posture.riskLevel != SecurityRiskLevel.LOW && !debugScreenshotsAllowed()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Debug builds only (BuildConfig.DEBUG is a compile-time false in release, so
    // R8 drops this): lets adb capture screens for test reports. See DebugHooksReceiver.
    private fun debugScreenshotsAllowed(): Boolean =
        com.privacyguard.app.BuildConfig.DEBUG &&
            getSharedPreferences("debug_hooks", MODE_PRIVATE).getBoolean("allow_screenshots", false)
}
