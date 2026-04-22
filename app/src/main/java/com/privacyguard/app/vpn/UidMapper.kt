package com.privacyguard.app.vpn

import com.privacyguard.app.core.app.AppInfo
import com.privacyguard.app.core.app.AppResolver
import com.privacyguard.app.utils.ProcNetParser
import java.util.concurrent.ConcurrentHashMap

object UidMapper {

    private val remoteEndpointToUid = ConcurrentHashMap<String, Int>()
    private var lastRefreshTime = 0L
    private const val UPDATE_INTERVAL_MS = 2_000L

    fun getUidForConnection(destinationIp: String, destinationPort: Int): Int {
        refreshIfNeeded()
        return remoteEndpointToUid["$destinationIp:$destinationPort"] ?: -1
    }

    fun getAppInfoForConnection(destinationIp: String, destinationPort: Int): AppInfo {
        val uid = getUidForConnection(destinationIp, destinationPort)
        return if (uid >= 0) AppResolver.getAppByUid(uid) else AppInfo(-1, "unknown", "Unknown")
    }

    fun cleanup() {
        remoteEndpointToUid.clear()
        lastRefreshTime = 0L
    }

    private fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshTime < UPDATE_INTERVAL_MS) return
        lastRefreshTime = now

        remoteEndpointToUid.clear()
        ProcNetParser.getActiveTcpConnections().forEach { connection ->
            remoteEndpointToUid["${connection.remoteIp}:${connection.remotePort}"] = connection.uid
        }
        ProcNetParser.getActiveUdpConnections().forEach { connection ->
            remoteEndpointToUid["${connection.remoteIp}:${connection.remotePort}"] = connection.uid
        }
    }
}
