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
        if (posture.riskLevel != SecurityRiskLevel.LOW) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
