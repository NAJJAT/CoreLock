package com.privacyguard.app.core.monitor

import android.content.Context
import android.net.ConnectivityManager
import com.privacyguard.app.utils.NetworkUtils

class NetworkMonitor(
    private val context: Context
) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(onNetworkStateChanged: (Boolean) -> Unit) {
        if (callback != null) return
        callback = NetworkUtils.registerNetworkCallback(context, onNetworkStateChanged)
    }

    fun stop() {
        NetworkUtils.unregisterNetworkCallback(context, callback)
        callback = null
    }
}
