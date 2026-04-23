package com.privacyguard.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.privacyguard.platform.android.PrivacyVpnService

/**
 * VPN manager for the refactored package layout.
 * Used by [com.privacyguard.MainActivity].
 */
class VpnManager(
    private val context: Context,
) {
    fun prepare(): Intent? = VpnService.prepare(context)

    fun start() {
        context.startService(PrivacyVpnService.startIntent(context))
    }

    fun stop() {
        context.startService(PrivacyVpnService.stopIntent(context))
    }

    val isRunning: Boolean get() = PrivacyVpnService.isRunning
}
