package com.privacyguard.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.privacyguard.platform.android.NotificationHelper

object KillSwitch {

    @Volatile private var enabled: Boolean = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun isEnabled(): Boolean = enabled

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
