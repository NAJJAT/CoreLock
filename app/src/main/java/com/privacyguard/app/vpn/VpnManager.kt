package com.privacyguard.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

object VpnManager {

    val isVpnRunning: Boolean
        get() = PrivacyVpnService.isRunning

    fun startVpn(context: Context) {
        val intent = Intent(context, PrivacyVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopVpn(context: Context) {
        val intent = Intent(context, PrivacyVpnService::class.java)
        context.stopService(intent)
    }

    fun prepareVpn(context: Context): Intent? {
        return VpnService.prepare(context)
    }
}
