package com.privacyguard.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Helper object for starting and stopping the VPN service.
 */
object VpnManager {

    fun prepareVpn(context: Context): Intent? = VpnService.prepare(context)

    fun startVpn(context: Context) {
        val intent = Intent(context, PrivacyVpnService::class.java)
        context.startService(intent)
    }

    fun stopVpn(context: Context) {
        val intent = Intent(context, PrivacyVpnService::class.java)
        intent.action = PrivacyVpnService.ACTION_STOP
        context.startService(intent)
    }
}
