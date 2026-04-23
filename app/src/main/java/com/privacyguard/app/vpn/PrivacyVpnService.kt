package com.privacyguard.app.vpn

import android.content.Intent
import android.net.VpnService

/**
 * Old-package stub — delegates to the real implementation in
 * [com.privacyguard.platform.android.PrivacyVpnService].
 *
 * Kept here so existing UI code that imports this package still compiles.
 */
class PrivacyVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Forward to the real service
        val real = Intent(this, com.privacyguard.platform.android.PrivacyVpnService::class.java)
        real.action = intent?.action
        startService(real)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = com.privacyguard.platform.android.PrivacyVpnService.ACTION_STOP

        val isRunning: Boolean
            get() = com.privacyguard.platform.android.PrivacyVpnService.isRunning

        @Volatile var isPcapEnabled: Boolean = false
    }
}
