package com.privacyguard.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import com.privacyguard.platform.android.NotificationHelper

/**
 * Two separate things live here:
 *
 * - The VPN drop alert ([enable]/[startMonitoring]): notifies and restarts the
 *   service when the tunnel goes away. It does NOT block traffic — once our
 *   tunnel is gone, an app cannot stop other apps from using the network.
 * - The real kill switch ([lockdownActive]): Android's Always-on VPN with
 *   "Block connections without VPN". Only the OS can enforce it, so the UI
 *   reports the kill switch as active only when Android says lockdown is on.
 */
object KillSwitch {

    @Volatile private var enabled: Boolean = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun isEnabled(): Boolean = enabled

    /** True only when Android itself blocks traffic if the VPN drops (API 29+). */
    @Volatile var lockdownActive: Boolean = false
        private set

    /** Call from the running service; isAlwaysOn/isLockdownEnabled only answer for the calling VpnService. */
    fun updateLockdownState(service: VpnService) {
        lockdownActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            service.isAlwaysOn && service.isLockdownEnabled
    }

    fun clearLockdownState() {
        lockdownActive = false
    }

    /** System VPN settings, where the user turns on Always-on VPN and "Block connections without VPN". */
    fun openAlwaysOnSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun enable() {
        enabled = true
    }

    fun disable() {
        enabled = false
    }

    fun startMonitoring(context: Context) {
        if (networkCallback != null) return
        val appCtx = context.applicationContext
        connectivityManager = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val helper = NotificationHelper(appCtx)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (!enabled) return
                helper.postKillSwitchAlert()
                runCatching {
                    appCtx.startService(
                        com.privacyguard.platform.android.PrivacyVpnService.startIntent(appCtx)
                    )
                }
            }

            override fun onAvailable(network: Network) {
                helper.cancelKillSwitchAlert()
            }
        }

        val cb = networkCallback ?: return
        connectivityManager?.registerNetworkCallback(request, cb)
    }

    fun stopMonitoring() {
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
            networkCallback = null
        }
        connectivityManager = null
    }
}
