package com.privacyguard.app.vpn

import com.privacyguard.app.core.app.AppInfo
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.packet.DnsMessage
import com.privacyguard.app.core.packet.IpPacket
import com.privacyguard.app.core.packet.TcpPacket
import com.privacyguard.app.core.packet.UdpPacket
import com.privacyguard.app.core.stats.StatsManager
import com.privacyguard.app.service.notification.NotificationService

sealed class PacketDecision {
    data object Pass : PacketDecision()
    data class Blocked(val reason: String) : PacketDecision()
}

class PacketProcessor {

    @Volatile
    private var notificationService: NotificationService? = null

    fun attachNotificationService(service: NotificationService) {
        notificationService = service
    }

    fun process(data: ByteArray, length: Int): PacketDecision {
        val ipPacket = IpPacket.parse(data, length) ?: return PacketDecision.Pass
        val destinationIp = IpPacket.ipToString(ipPacket.destinationAddress)
        val appInfo = resolveAppInfo(ipPacket, destinationIp)

        return when {
            ipPacket.isTcp() -> processTcp(ipPacket, destinationIp, appInfo)
            ipPacket.isUdp() -> processUdp(ipPacket, destinationIp, appInfo)
            else -> {
                StatsManager.recordPacket(
                    appUid = appInfo.uid,
                    appName = appInfo.appName,
                    packageName = appInfo.packageName,
                    destination = destinationIp,
                    destinationIp = destinationIp,
                    destinationPort = 0,
                    protocol = "IP",
                    bytes = length,
                    isBlocked = false
                )
                PacketDecision.Pass
            }
        }
    }

    private fun processTcp(ipPacket: IpPacket, destinationIp: String, appInfo: AppInfo): PacketDecision {
        val tcpPacket = TcpPacket.parse(ipPacket.payload, ipPacket.payload.size) ?: return PacketDecision.Pass
        val connectionId = "TCP:$destinationIp:${tcpPacket.destinationPort}"

        StatsManager.recordPacket(
            appUid = appInfo.uid,
            appName = appInfo.appName,
            packageName = appInfo.packageName,
            destination = destinationIp,
            destinationIp = destinationIp,
            destinationPort = tcpPacket.destinationPort,
            protocol = "TCP",
            bytes = ipPacket.totalLength,
            isBlocked = false
        )

        if (tcpPacket.isFin() || tcpPacket.isRst()) {
            StatsManager.removeConnection(connectionId)
        } else if (tcpPacket.hasData() || tcpPacket.isSyn()) {
            StatsManager.recordAllowedActivity(appInfo.appName, "$destinationIp:${tcpPacket.destinationPort}")
        }

        return PacketDecision.Pass
    }

    private fun processUdp(ipPacket: IpPacket, destinationIp: String, appInfo: AppInfo): PacketDecision {
        val udpPacket = UdpPacket.parse(ipPacket.payload, ipPacket.payload.size) ?: return PacketDecision.Pass
        val protocol = if (udpPacket.destinationPort == 53 || udpPacket.sourcePort == 53) "DNS" else "UDP"
        val destinationLabel = if (protocol == "DNS") "DNS" else destinationIp

        StatsManager.recordPacket(
            appUid = appInfo.uid,
            appName = appInfo.appName,
            packageName = appInfo.packageName,
            destination = destinationLabel,
            destinationIp = destinationIp,
            destinationPort = udpPacket.destinationPort,
            protocol = protocol,
            bytes = ipPacket.totalLength,
            isBlocked = false
        )

        if (udpPacket.destinationPort == 53 || udpPacket.sourcePort == 53) {
            val dnsMessage = DnsMessage.parse(udpPacket.payload, udpPacket.payload.size)
            val domain = dnsMessage?.getPrimaryDomain()?.lowercase()
            if (!domain.isNullOrBlank()) {
                val match = BlocklistManager.findMatch(domain)
                if (match != null) {
                    StatsManager.recordBlock(
                        domain = domain,
                        appUid = appInfo.uid,
                        appName = appInfo.appName,
                        packageName = appInfo.packageName,
                        category = match.category.name
                    )
                    notificationService?.connectionBlocked(appInfo.appName, domain)
                    notificationService?.trackerBlocked(domain, appInfo.appName)
                    return PacketDecision.Blocked("DNS blocked for $domain")
                }

                StatsManager.recordAllowedActivity(appInfo.appName, domain)
            }
        }

        return PacketDecision.Pass
    }

    private fun resolveAppInfo(ipPacket: IpPacket, destinationIp: String): AppInfo {
        val destinationPort = when {
            ipPacket.isTcp() -> TcpPacket.parse(ipPacket.payload, ipPacket.payload.size)?.destinationPort ?: 0
            ipPacket.isUdp() -> UdpPacket.parse(ipPacket.payload, ipPacket.payload.size)?.destinationPort ?: 0
            else -> 0
        }
        return UidMapper.getAppInfoForConnection(destinationIp, destinationPort)
    }
}
