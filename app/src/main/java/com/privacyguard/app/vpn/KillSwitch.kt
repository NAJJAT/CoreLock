package com.privacyguard.app.vpn

import android.content.Context

object KillSwitch {

    @Volatile private var enabled: Boolean = false

    fun isEnabled(): Boolean = enabled

    fun enable() {
        enabled = true
    }

    fun disable() {
        enabled = false
    }

    fun startMonitoring(context: Context) {
        // Stub — real implementation would monitor VPN connectivity
    }
}
