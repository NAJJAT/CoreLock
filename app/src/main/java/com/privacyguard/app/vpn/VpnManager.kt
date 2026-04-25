package com.privacyguard.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/**
 * Helper object for starting and stopping the VPN service.
 * Always delegates to the real [com.privacyguard.platform.android.PrivacyVpnService]
 * so that Android's VPN permission (granted via VpnService.prepare) applies to
 * the service that actually calls Builder().establish().
 */
object VpnManager {

    fun prepareVpn(context: Context): Intent? = VpnService.prepare(context)

    fun startVpn(context: Context) {
        ContextCompat.startForegroundService(
            context,
            com.privacyguard.platform.android.PrivacyVpnService.startIntent(context)
        )
    }

    fun stopVpn(context: Context) {
        context.startService(
            com.privacyguard.platform.android.PrivacyVpnService.stopIntent(context)
        )
    }
}
