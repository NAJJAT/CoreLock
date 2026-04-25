package com.privacyguard.vpn.forwarder

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.utils.Checksum
import com.privacyguard.vpn.inspector.DnsAnomalyDetector
import com.privacyguard.vpn.tunnel.TunWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import android.util.Log

/**
 * DNS Shield — intercepts all DNS traffic and applies two layers of protection:
 *
 *  Layer 1 — **Blocklist**:     domain in EasyList/OISD? → NXDOMAIN immediately.
 *  Layer 2 — **Anomaly detection**: behavioral analysis via [DnsAnomalyDetector]
 *             detects DNS tunneling, DGA beacons, and high-frequency tracking.
 *
 * Additionally reverse-maps IP→hostname for the [com.privacyguard.core.session.Session]
 * and [com.privacyguard.core.metadata.MetadataEngine] — the only reliable way to
 * attach a human-readable name to a TCP/UDP session is to intercept the DNS
 * query that preceded it.
 *
 * Zero payload decryption. All analysis is on DNS query names only.
 */
class DnsHandler(
    private val filterEngine:      FilterEngine,
    private val tunWriter:         TunWriter,
    private val anomalyDetector:   DnsAnomalyDetector = DnsAnomalyDetector(),
    private val upstreamDns:       String             = DEFAULT_UPSTREAM_DNS,
    private val upstreamPort:      Int                = DNS_PORT,
    private val upstreamTimeoutMs: Int                = 3_000,
    @Volatile var dohEnabled:      Boolean            = false,
    @Volatile var dohProvider:     String             = DOH_CLOUDFLARE,
    // Must be VpnService.protect() — without this, forwarded DNS sockets get
    // re-intercepted by the VPN and loop infinitely instead of reaching the upstream.
    private val protectSocket:     ((DatagramSocket) -> Boolean)? = null,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    /** Notified when a DNS A-record response maps an IP to a hostname. */
    fun interface ResolvedListener {
        fun onResolved(ip: String, hostname: String)
    }

    /** Notified when the anomaly detector raises an alert. */
    fun interface AnomalyListener {
        fun onAnomaly(anomaly: DnsAnomalyDetector.Anomaly)
    }

    @Volatile var resolvedListener: ResolvedListener? = null
    @Volatile var anomalyListener:  AnomalyListener?  = null

    // ─────────────────────────────────────────────────────────────────────────
    // Entry Point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handles a UDP packet whose destination or source port is 53.
     *
     * @return true if the packet was consumed (blocked or forwarded by us),
     *         false if it should fall through to the generic UDP forwarder.
     */
    fun handle(
        ipPacket:    IpPacket,
        udpPacket:   UdpPacket,
        ownerPackage: String? = null,
    ): Boolean {
        if (!udpPacket.isDns) return false
        if (udpPacket.data.isEmpty()) return true

        val dns = DnsPacket.parse(udpPacket.data) ?: run {
            malformedDns.incrementAndGet()
            return true
        }

        // Only intercept client queries; let responses pass-through
        if (dns.isResponse) return false

        val queryName = dns.queryName ?: return false

        // ── Layer 2: Anomaly Detection (always, before blocking) ─────────────
        val anomalies = anomalyDetector.analyze(ownerPackage, queryName)
        anomalies.forEach { anomalyListener?.onAnomaly(it) }

        // ── Layer 1: Blocklist ───────────────────────────────────────────────
        if (filterEngine.isDomainBlocked(queryName)) {
            blockedCount.incrementAndGet()
            val nxdomain = DnsPacket.buildBlockedResponse(dns)
            val response = buildUdpResponse(
                srcIp   = ipPacket.destinationIp,
                dstIp   = ipPacket.sourceIp,
                srcPort = DNS_PORT,
                dstPort = udpPacket.sourcePort,
                data    = nxdomain,
            )
            tunWriter.enqueueWithChecksums(response)
            return true
        }

        // ── Forward to upstream DNS (plain UDP or DoH) ───────────────────────
        forwardCount.incrementAndGet()
        val label = "dns-fwd-${dns.id}"
        if (dohEnabled) {
            Thread(null, { forwardViaDoh(ipPacket, udpPacket, dns, ownerPackage) }, label)
                .also { it.isDaemon = true; it.start() }
        } else {
            Thread(null, { forwardBlocking(ipPacket, udpPacket, dns, ownerPackage) }, label)
                .also { it.isDaemon = true; it.start() }
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Upstream Forwarding
    // ─────────────────────────────────────────────────────────────────────────

    private fun forwardBlocking(
        ipPacket:     IpPacket,
        udpPacket:    UdpPacket,
        dnsQuery:     DnsPacket,
        ownerPackage: String?,
    ) {
        val serversToTry = buildList {
            add(upstreamDns)
            FALLBACK_DNS_SERVERS.forEach { if (it != upstreamDns) add(it) }
        }
        for (server in serversToTry) {
            if (tryForwardToServer(server, ipPacket, udpPacket, dnsQuery, ownerPackage)) return
        }
        upstreamErrors.incrementAndGet()
        Log.e(TAG, "All DNS servers failed for ${dnsQuery.queryName}")
    }

    private fun tryForwardToServer(
        server:       String,
        ipPacket:     IpPacket,
        udpPacket:    UdpPacket,
        dnsQuery:     DnsPacket,
        ownerPackage: String?,
    ): Boolean {
        return try {
            DatagramSocket().use { socket ->
                // Exclude this socket from VPN re-interception — without protect() the
                // DNS query loops back into the handler instead of reaching the internet.
                val protected = protectSocket?.invoke(socket) ?: true
                if (!protected) {
                    Log.e(TAG, "VpnService.protect() returned false for DNS socket to $server — socket is unprotected!")
                    return@use false
                }
                socket.soTimeout = upstreamTimeoutMs

                val upstream   = InetAddress.getByName(server)
                val queryBytes = udpPacket.data
                socket.send(DatagramPacket(queryBytes, queryBytes.size, upstream, upstreamPort))

                // 4096-byte buffer: handles EDNS0 responses and avoids TC (truncation) flag
                // that would cause the device's resolver to retry over TCP (unsupported here).
                val responseBuf = ByteArray(MAX_DNS_PAYLOAD)
                val recv        = DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(recv)

                val responseData = responseBuf.copyOf(recv.length)
                injectDnsResponse(ipPacket, udpPacket, dnsQuery, responseData, ownerPackage)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "DNS forward to $server failed for ${dnsQuery.queryName}: ${e.message}")
            false
        }
    }

    private fun injectDnsResponse(
        ipPacket:     IpPacket,
        udpPacket:    UdpPacket,
        dnsQuery:     DnsPacket,
        responseData: ByteArray,
        @Suppress("UNUSED_PARAMETER") ownerPackage: String?,
    ) {
        val dnsResponse = DnsPacket.parse(responseData)
        if (dnsResponse != null) {
            val queryName = dnsQuery.queryName
            if (queryName != null) {
                for (ip in dnsResponse.aRecords)    resolvedListener?.onResolved(ip, queryName)
                for (ip in dnsResponse.aaaaRecords) resolvedListener?.onResolved(ip, queryName)
            }
        }
        tunWriter.enqueueWithChecksums(buildUdpResponse(
            srcIp   = ipPacket.destinationIp,
            dstIp   = ipPacket.sourceIp,
            srcPort = DNS_PORT,
            dstPort = udpPacket.sourcePort,
            data    = responseData,
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DoH Forwarding (RFC 8484)
    // ─────────────────────────────────────────────────────────────────────────

    private fun forwardViaDoh(
        ipPacket:     IpPacket,
        udpPacket:    UdpPacket,
        dnsQuery:     DnsPacket,
        ownerPackage: String?,
    ) {
        try {
            val queryBytes = udpPacket.data
            val conn = URL(dohProvider).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.setRequestProperty("Content-Length", queryBytes.size.toString())
            conn.connectTimeout = upstreamTimeoutMs
            conn.readTimeout = upstreamTimeoutMs
            conn.doOutput = true
            conn.outputStream.use { it.write(queryBytes) }

            if (conn.responseCode in 200..299) {
                val responseData = conn.inputStream.use { it.readBytes() }
                injectDnsResponse(ipPacket, udpPacket, dnsQuery, responseData, ownerPackage)
            } else {
                upstreamErrors.incrementAndGet()
                forwardBlocking(ipPacket, udpPacket, dnsQuery, ownerPackage)
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH error for ${dnsQuery.queryName}: ${e.message}")
            upstreamErrors.incrementAndGet()
            forwardBlocking(ipPacket, udpPacket, dnsQuery, ownerPackage)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet Builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildUdpResponse(
        srcIp: String, dstIp: String,
        srcPort: Int, dstPort: Int,
        data: ByteArray,
    ): ByteArray {
        val udpLen    = 8 + data.size
        val totalLen  = 20 + udpLen
        val pkt       = ByteArray(totalLen)

        pkt[0] = 0x45.toByte()
        pkt[2] = (totalLen ushr 8).toByte(); pkt[3] = (totalLen and 0xFF).toByte()
        pkt[8] = 64.toByte(); pkt[9] = 17.toByte()
        writeIp(pkt, 12, srcIp); writeIp(pkt, 16, dstIp)
        pkt[20] = (srcPort ushr 8).toByte(); pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = (dstPort ushr 8).toByte(); pkt[23] = (dstPort and 0xFF).toByte()
        pkt[24] = (udpLen  ushr 8).toByte(); pkt[25] = (udpLen  and 0xFF).toByte()
        if (data.isNotEmpty()) data.copyInto(pkt, 28)

        Checksum.setIpv4HeaderChecksum(pkt, 0)
        Checksum.setUdpChecksum(pkt, 0)
        return pkt
    }

    private fun writeIp(buf: ByteArray, offset: Int, ip: String) {
        ip.split('.').forEachIndexed { i, s -> buf[offset + i] = (s.toInt() and 0xFF).toByte() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics
    // ─────────────────────────────────────────────────────────────────────────

    val blockedCount   = AtomicLong(0)
    val forwardCount   = AtomicLong(0)
    val malformedDns   = AtomicLong(0)
    val upstreamErrors = AtomicLong(0)

    data class Stats(
        val blocked:        Long,
        val forwarded:      Long,
        val malformed:      Long,
        val upstreamErrors: Long,
        val anomalyStats:   AnomalyStats,
        val upstreamDns:    String,
    )

    data class AnomalyStats(
        val totalAnalyzed:    Long,
        val totalNewDomains:  Long,
        val detectedAnomalies: Long,
        val seenDomains:      Int,
    )

    fun stats() = Stats(
        blocked        = blockedCount.get(),
        forwarded      = forwardCount.get(),
        malformed      = malformedDns.get(),
        upstreamErrors = upstreamErrors.get(),
        anomalyStats   = AnomalyStats(
            totalAnalyzed     = anomalyDetector.totalAnalyzed.get(),
            totalNewDomains   = anomalyDetector.totalNewDomains.get(),
            detectedAnomalies = anomalyDetector.detectedAnomalies.get(),
            seenDomains       = anomalyDetector.seenDomainCount(),
        ),
        upstreamDns    = upstreamDns,
    )

    companion object {
        private const val TAG = "DnsHandler"

        const val DNS_PORT             = 53
        const val DEFAULT_UPSTREAM_DNS = "1.1.1.1"

        // 4096 bytes covers EDNS0 responses. Without this, responses larger than
        // 512 bytes get truncated (TC=1), the device retries over DNS-over-TCP
        // (which this handler doesn't support), and resolution fails entirely.
        const val MAX_DNS_PAYLOAD = 4096

        // Automatic fallback chain if the primary DNS is unreachable.
        val FALLBACK_DNS_SERVERS = listOf("8.8.8.8", "9.9.9.9")

        const val DOH_CLOUDFLARE = "https://cloudflare-dns.com/dns-query"
        const val DOH_GOOGLE     = "https://dns.google/dns-query"
        const val DOH_QUAD9      = "https://dns.quad9.net/dns-query"
    }
}