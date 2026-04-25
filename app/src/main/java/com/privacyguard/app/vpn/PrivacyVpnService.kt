package com.privacyguard.app.vpn

/**
 * Compatibility shim so existing UI code can reference
 * [com.privacyguard.app.vpn.PrivacyVpnService.isRunning] without changes.
 *
 * The real service is [com.privacyguard.platform.android.PrivacyVpnService],
 * which is the only class registered in the manifest with the
 * android.net.VpnService intent filter.
 */
object PrivacyVpnService {
    val isRunning: Boolean
        get() = com.privacyguard.platform.android.PrivacyVpnService.isRunning

    const val ACTION_STOP: String =
        com.privacyguard.platform.android.PrivacyVpnService.ACTION_STOP

    @Volatile var isPcapEnabled: Boolean = false
}
