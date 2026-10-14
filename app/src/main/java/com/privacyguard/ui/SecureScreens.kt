package com.privacyguard.ui

import android.view.Window
import android.view.WindowManager

/**
 * FLAG_SECURE (no screenshots, screen recording or Recents thumbnail) for every
 * screen that shows history, hosts, rules or payloads. Secure by default: a route
 * is exempt only if it is listed in [NOT_SENSITIVE], so a new screen is protected
 * without anyone remembering to do it (V-08).
 */
object SecureScreens {

    /** Routes that show no collected data. */
    val NOT_SENSITIVE = setOf("onboarding")

    /** The device looks compromised (AppSecurityMonitor): every screen is secure. */
    @Volatile var deviceForcesSecure = false

    /** Debug builds only: set by DebugHooksReceiver so adb can capture test screens. */
    @Volatile var debugScreenshotsAllowed = false

    fun isSensitive(route: String?): Boolean =
        route?.substringBefore('/')?.substringBefore('?') !in NOT_SENSITIVE

    fun shouldSecure(route: String?): Boolean =
        !debugScreenshotsAllowed && (deviceForcesSecure || isSensitive(route))

    fun apply(window: Window, route: String?) {
        if (shouldSecure(route)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
