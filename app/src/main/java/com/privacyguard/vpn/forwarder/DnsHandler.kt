package com.privacyguard.vpn.forwarder

import com.privacyguard.app.core.utils.readBytesCapped
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.packet.DnsPacket
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.utils.Checksum
import com.privacyguard.vpn.firewall.DomainFilter
import com.privacyguard.vpn.inspector.DnsAnomalyDetector
import com.privacyguard.vpn.tunnel.TunWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocketFactory
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
    // Changeable while running (Settings → Upstream DNS); read once per query.
    @Volatile var upstreamDns:     String             = DEFAULT_UPSTREAM_DNS,
    private val upstreamPort:      Int                = DNS_PORT,
    private val upstreamTimeoutMs: Int                = 3_000,
    @Volatile var dohEnabled:      Boolean            = false,
    @Volatile var dohProvider:     String             = DOH_CLOUDFLARE,
    // Must be VpnService.protect() — without this, forwarded DNS sockets get
    // re-intercepted by the VPN and loop infinitely instead of reaching the upstream.
    private val protectSocket:     ((DatagramSocket) -> Boolean)? = null,
    private val protectTcpSocket:  ((Socket) -> Boolean)? = null,
    private val domainFilter:      DomainFilter? = null,
    private val adSdkPolicy:       com.privacyguard.app.adprivacy.AdSdkPolicy? = null,
    // Dual-hop: DoH goes through the SOCKS chain like every other TCP connection.
    private val dualVpnConfig:     () -> com.privacyguard.vpn.dualvpn.DualVpnConfig = { com.privacyguard.vpn.dualvpn.DualVpnConfig() },
    // Malware and phishing hosts: only an Allow rule the user confirmed after a warning lets them through.
    private val securityFilter:    DomainFilter? = null,
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

    /** Notified for every client DNS query after the block decision is known. */
    fun interface QueryListener {
        fun onQuery(ownerPackage: String?, domain: String, wasBlocked: Boolean)
    }

    /** Signs about the network itself: type is a NetworkSignals constant. */
    fun interface NetworkSignalListener {
        fun onSignal(type: String, domain: String, detail: String)
    }

    @Volatile var resolvedListener: ResolvedListener? = null
    @Volatile var anomalyListener:  AnomalyListener?  = null
    @Volatile var queryListener:    QueryListener?    = null
    @Volatile var networkSignalListener: NetworkSignalListener? = null

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
        // Everything an app sends to port 53 is answered here or dropped, never handed
        // to the generic UDP forwarder: a datagram passed on there opened a UDP session,
        // and later datagrams of that session skipped this check (V-02).
        if (udpPacket.destinationPort != DNS_PORT) return false
        if (udpPacket.data.isEmpty()) return true

        val dns = DnsPacket.parse(udpPacket.data) ?: run {
            malformedDns.incrementAndGet()
            return true
        }

        // Only client queries with exactly one question are forwarded; anything else is
        // dropped. Only the first question was checked, so a second one in the same
        // query (which some resolvers answer) went through unfiltered (V-16).
        if (dns.isResponse || dns.questions.size != 1) { malformedDns.incrementAndGet(); return true }
        val queryName = dns.queryName ?: run { malformedDns.incrementAndGet(); return true }

        // ── Layer 2: Anomaly Detection (always, before blocking) ─────────────
        val anomalies = anomalyDetector.analyze(ownerPackage, queryName)
        anomalies.forEach { anomalyListener?.onAnomaly(it) }

        // ── Layer 1: Blocklist ───────────────────────────────────────────────
        val blockReason = blockReasonFor(queryName, ownerPackage)
        if (blockReason != null) {
            queryListener?.onQuery(ownerPackage, queryName, true)
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
        queryListener?.onQuery(ownerPackage, queryName, false)
        forwardCount.incrementAndGet()
        // Bounded pool: one new thread per query let any app flood DNS until the VPN
        // process ran out of threads/memory. Overflow is dropped; resolvers retry.
        try {
            if (dohEnabled) {
                // Guarded: an exception in an execute() task ends the process.
                forwardExecutor.execute { com.privacyguard.vpn.WorkerGuard.guard("dns-doh") { forwardViaDoh(ipPacket, udpPacket, dns, ownerPackage) } }
            } else {
                forwardExecutor.execute { com.privacyguard.vpn.WorkerGuard.guard("dns") { forwardBlocking(ipPacket, udpPacket, dns, ownerPackage) } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            droppedQueries.incrementAndGet()
        }
        return true
    }

    /** The decision DNS shares with CNAME targets and TLS/QUIC SNI. */
    val nameBlockPolicy = NameBlockPolicy(filterEngine, domainFilter, securityFilter, adSdkPolicy)

    /** Why [name] is blocked for [ownerPackage], or null: see [NameBlockPolicy]. */
    fun blockReasonFor(name: String, ownerPackage: String?): DnsBlockDecision.Reason? =
        nameBlockPolicy.reasonFor(name, ownerPackage)

    /**
     * The first CNAME target in [response] that is blocked for [ownerPackage] (CNAME
     * cloaking), or null. A user Allow for [askedName] wins over its aliases' ad and
     * tracker lists; a malware or phishing alias still blocks unless that Allow was
     * confirmed after the warning, the same order the name itself gets.
     */
    internal fun blockedCnameTarget(response: DnsPacket, ownerPackage: String?, askedName: String? = null): String? {
        val allow = askedName?.let { nameBlockPolicy.allowRuleFor(it, ownerPackage) }
        return response.cnameRecords.firstOrNull { target ->
            val reason = blockReasonFor(target, ownerPackage) ?: return@firstOrNull false
            allow == null || (reason == DnsBlockDecision.Reason.SECURITY_LIST && !allow.overridesSecurity)
        }
    }

    private val forwardExecutor = java.util.concurrent.ThreadPoolExecutor(
        // core == max: with a bounded queue, extra threads would start only once it is full.
        MAX_FORWARD_THREADS, MAX_FORWARD_THREADS, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(MAX_PENDING_QUERIES),
        { r -> Thread(r, "dns-fwd").also { it.isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    ).also { it.allowCoreThreadTimeOut(true) }

    /** Stops forwarding threads when the VPN stops. */
    fun shutdown() {
        forwardExecutor.shutdownNow()
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
        // Only the provider the user chose: falling back to another company's
        // resolver would send them every name without the user ever choosing them.
        val primary = upstreamDns
        val serversToTry = listOfNotNull(primary, SECONDARY_ADDRESS[primary])
        for (server in serversToTry) {
            if (tryForwardToServer(server, ipPacket, udpPacket, dnsQuery, ownerPackage)) return
        }
        upstreamErrors.incrementAndGet()
        Log.e(TAG, "All DNS servers failed")
        answerServfail(ipPacket, udpPacket, dnsQuery)
    }

    /** Tells the app the lookup failed now, rather than letting it wait for its own timeout. */
    private fun answerServfail(ipPacket: IpPacket, udpPacket: UdpPacket, dnsQuery: DnsPacket) {
        val servfail = DnsPacket.buildServfailResponse(dnsQuery)
        if (servfail.isEmpty()) return
        tunWriter.enqueueWithChecksums(buildUdpResponse(
            srcIp   = ipPacket.destinationIp,
            dstIp   = ipPacket.sourceIp,
            srcPort = DNS_PORT,
            dstPort = udpPacket.sourcePort,
            data    = servfail,
        ))
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
                    Log.e(TAG, "VpnService.protect() returned false for DNS socket — socket is unprotected!")
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

                // Only accept the answer from the server we asked; anything else on
                // this port is spoofed or stray.
                if (recv.address != upstream || recv.port != upstreamPort) return@use false
                val responseData = responseBuf.copyOf(recv.length)
                injectDnsResponse(ipPacket, udpPacket, dnsQuery, responseData, ownerPackage, viaDoh = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "DNS forward failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Asks the upstream resolver, over plain DNS, for a name under ".invalid" that
     * cannot exist. An address in the answer means the network intercepts or rewrites
     * DNS. Returns null when no usable answer came back. Runs off the packet path.
     */
    /**
     * Diagnostics: one real lookup over the path queries take right now, DoH when it
     * is on, otherwise plain UDP to the chosen resolver, on a protected socket.
     */
    fun probeUpstream(): Boolean = runCatching {
        val id = (Math.random() * 0xFFFF).toInt()
        val query = DnsPacket.buildQuery("example.com", id)
        val reply = if (dohEnabled) postDoh(query) else DatagramSocket().use { socket ->
            if (protectSocket?.invoke(socket) == false) return false
            socket.soTimeout = upstreamTimeoutMs
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(upstreamDns), upstreamPort))
            val buf = ByteArray(MAX_DNS_PAYLOAD)
            val recv = DatagramPacket(buf, buf.size)
            socket.receive(recv)
            buf.copyOf(recv.length)
        }
        reply?.let { DnsPacket.parse(it) }?.let { it.isResponse && it.id == id } == true
    }.getOrDefault(false)

    fun probeHijack(): Boolean? {
        val name = com.privacyguard.app.core.network.NetworkSignals.hijackProbeName(
            java.util.UUID.randomUUID().toString().replace("-", "").take(16))
        val id = (Math.random() * 0xFFFF).toInt()
        return runCatching {
            DatagramSocket().use { socket ->
                if (protectSocket?.invoke(socket) == false) return null
                socket.soTimeout = upstreamTimeoutMs
                val upstream = InetAddress.getByName(upstreamDns)
                val query = DnsPacket.buildQuery(name, id)
                socket.send(DatagramPacket(query, query.size, upstream, upstreamPort))
                val buf = ByteArray(MAX_DNS_PAYLOAD)
                val recv = DatagramPacket(buf, buf.size)
                socket.receive(recv)
                val response = DnsPacket.parse(buf.copyOf(recv.length))?.takeIf { it.id == id }
                com.privacyguard.app.core.network.NetworkSignals.isHijacked(response)
            }
        }.getOrNull()
    }

    private fun injectDnsResponse(
        ipPacket:     IpPacket,
        udpPacket:    UdpPacket,
        dnsQuery:     DnsPacket,
        responseData: ByteArray,
        ownerPackage: String?,
        viaDoh: Boolean = true,
    ): Boolean {
        // Must be a parseable answer to this very query: otherwise a forged or
        // mismatched response (or an HTTP body that is not a DNS message) would be
        // handed to the app and poison the IP→hostname map.
        val dnsResponse = DnsPacket.parse(responseData)
        if (dnsResponse == null || !dnsResponse.isResponse || dnsResponse.id != dnsQuery.id) {
            Log.w(TAG, "Dropping DNS response that does not match the query")
            return false
        }
        // CNAME cloaking: an allowed first-party name that is an alias of a blocked
        // tracker resolves to the tracker. Answer NXDOMAIN as for the tracker itself,
        // unless the user allowed the name the app asked for.
        blockedCnameTarget(dnsResponse, ownerPackage, dnsQuery.queryName)?.let { target ->
            queryListener?.onQuery(ownerPackage, target, true)
            cnameBlocked.incrementAndGet()
            tunWriter.enqueueWithChecksums(buildUdpResponse(
                srcIp   = ipPacket.destinationIp,
                dstIp   = ipPacket.sourceIp,
                srcPort = DNS_PORT,
                dstPort = udpPacket.sourcePort,
                data    = DnsPacket.buildBlockedResponse(dnsQuery),
            ))
            return true
        }
        val queryName = dnsQuery.queryName
        if (queryName != null) {
            // Plain DNS can be changed on the way; DoH answers cannot, so only check those here.
            // Only against the built-in resolvers: some custom ones block by answering 0.0.0.0.
            if (!viaDoh && upstreamDns in SECONDARY_ADDRESS) {
                val answers = dnsResponse.aRecords + dnsResponse.aaaaRecords
                if (com.privacyguard.app.core.network.NetworkSignals.looksTampered(queryName, answers)) {
                    networkSignalListener?.onSignal(
                        com.privacyguard.app.core.network.NetworkSignals.DNS_TAMPERED, queryName,
                        "Public name answered with ${answers.joinToString()}",
                    )
                }
            }
            for (ip in dnsResponse.aRecords)    resolvedListener?.onResolved(ip, queryName)
            for (ip in dnsResponse.aaaaRecords) resolvedListener?.onResolved(ip, queryName)
        }
        tunWriter.enqueueWithChecksums(buildUdpResponse(
            srcIp   = ipPacket.destinationIp,
            dstIp   = ipPacket.sourceIp,
            srcPort = DNS_PORT,
            dstPort = udpPacket.sourcePort,
            data    = responseData,
        ))
        return true
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
        // With DoH on, a failed lookup is answered with SERVFAIL. Falling back to
        // plain DNS here used to send the name unencrypted without telling anyone,
        // which is exactly what the user turned DoH on to avoid.
        repeat(DOH_ATTEMPTS) {
            try {
                val responseData = postDoh(udpPacket.data)
                if (responseData != null &&
                    injectDnsResponse(ipPacket, udpPacket, dnsQuery, responseData, ownerPackage)) {
                    return
                }
            } catch (e: Exception) {
                // No hostname: warnings survive R8 and would put browsing history in logcat.
                Log.w(TAG, "DoH error: ${e.javaClass.simpleName}")
                // Our DoH provider's certificate was rejected: someone swapped it on the way.
                if (com.privacyguard.app.core.network.NetworkSignals.isCertificateRejection(e)) {
                    networkSignalListener?.onSignal(
                        com.privacyguard.app.core.network.NetworkSignals.TLS_INTERCEPTION,
                        runCatching { URL(dohProvider).host }.getOrDefault(dohProvider),
                        "Certificate of the encrypted DNS server was not trusted",
                    )
                }
            }
        }
        upstreamErrors.incrementAndGet()
        dohFailures.incrementAndGet()
        answerServfail(ipPacket, udpPacket, dnsQuery)
    }

    private fun postDoh(queryBytes: ByteArray): ByteArray? {
        val url = URL(dohProvider)
        val host = url.host
        val port = if (url.port > 0) url.port else 443
        val path = if (url.query.isNullOrBlank()) url.path else "${url.path}?${url.query}"

        // With dual-hop on, the DoH connection goes through both SOCKS servers; hop 2
        // resolves the provider's name, so nothing about it is looked up locally.
        val chain = dualVpnConfig().takeIf { it.isValid }
        val socket = if (chain != null) {
            runCatching {
                com.privacyguard.vpn.dualvpn.DualVpnTunnel.connect(chain, host, port, upstreamTimeoutMs, protectTcpSocket)
            }.getOrElse { Log.w(TAG, "DoH via dual-hop failed: ${it.javaClass.simpleName}"); return null }
        } else Socket()
        socket.use { rawSocket ->
            if (chain == null) {
                // An unconnected Socket has no file descriptor yet, so protect() always
                // returned false and every DoH query silently fell back to plain DNS.
                // Binding creates the descriptor.
                rawSocket.bind(null)
                val protected = protectTcpSocket?.invoke(rawSocket) ?: true
                if (!protected) {
                    Log.e(TAG, "VpnService.protect() returned false for DoH socket")
                    return null
                }
                // Connect by address: resolving the DoH host would go back through this
                // tunnel to this same DoH path. TLS below still verifies the hostname.
                val address = DOH_BOOTSTRAP[host]?.let { InetAddress.getByName(it) }
                rawSocket.connect(if (address != null) InetSocketAddress(address, port) else InetSocketAddress(host, port), upstreamTimeoutMs)
            }
            rawSocket.soTimeout = upstreamTimeoutMs

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = sslFactory.createSocket(rawSocket, host, port, true) as javax.net.ssl.SSLSocket
            // A raw SSLSocket checks the chain but not the name: without this, any
            // valid certificate for any host was accepted for the DoH server (V-01).
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                if (!isIpLiteral(host)) serverNames = listOf(javax.net.ssl.SNIHostName(host))
            }
            ssl.startHandshake()
            ssl.use { secureSocket ->
                val requestHeaders = buildString {
                    append("POST $path HTTP/1.1\r\n")
                    append("Host: $host\r\n")
                    append("Content-Type: application/dns-message\r\n")
                    append("Accept: application/dns-message\r\n")
                    append("Content-Length: ${queryBytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)

                val out = secureSocket.getOutputStream()
                out.write(requestHeaders)
                out.write(queryBytes)
                out.flush()

                // A DNS message is at most 64 KB; allow for HTTP headers.
                val rawResponse = secureSocket.getInputStream().readBytesCapped(80L * 1024)
                val headerEnd = rawResponse.findHeaderEnd() ?: return null
                val headerText = rawResponse.copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1)
                if (!headerText.startsWith("HTTP/1.1 2") && !headerText.startsWith("HTTP/1.0 2")) return null
                return rawResponse.copyOfRange(headerEnd + 4, rawResponse.size)
            }
        }
    }

    private fun ByteArray.findHeaderEnd(): Int? {
        for (i in 0 until size - 3) {
            if (this[i] == '\r'.code.toByte() &&
                this[i + 1] == '\n'.code.toByte() &&
                this[i + 2] == '\r'.code.toByte() &&
                this[i + 3] == '\n'.code.toByte()) {
                return i
            }
        }
        return null
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
    val droppedQueries = AtomicLong(0)
    /** Answers turned into NXDOMAIN because a CNAME pointed at a blocked name. */
    val cnameBlocked   = AtomicLong(0)
    /** DoH lookups answered with SERVFAIL because DoH could not be reached. */
    val dohFailures    = AtomicLong(0)

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

        // Upstream timeout is 3 s, so 32 threads sustain ~10 queries/s even when
        // every server is down; the queue absorbs bursts (page loads).
        private const val MAX_FORWARD_THREADS = 32
        private const val MAX_PENDING_QUERIES = 512

        /** Each built-in provider's second anycast address: same operator, same privacy terms. */
        val SECONDARY_ADDRESS = mapOf(
            "1.1.1.1" to "1.0.0.1",
            "8.8.8.8" to "8.8.4.4",
            "9.9.9.9" to "149.112.112.112",
        )

        private const val DOH_ATTEMPTS = 2

        private val IPV4_LITERAL = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
        private val IPV6_GROUP = Regex("^[0-9a-fA-F]{1,4}$")

        /**
         * True for a complete IPv4 or IPv6 address literal. The upstream resolver must
         * be one: a hostname (or a half-typed "1.1.") would need a DNS lookup that
         * goes through this tunnel's own resolver.
         */
        fun isIpLiteral(value: String): Boolean {
            if (IPV4_LITERAL.matches(value)) return true
            if (!value.contains(':') || value.count { it == ':' } > 7) return false
            val halves = value.split("::")
            if (halves.size > 2) return false
            val groups = halves.flatMap { half -> if (half.isEmpty()) emptyList() else half.split(':') }
            if (groups.any { !IPV6_GROUP.matches(it) }) return false
            return if (halves.size == 2) groups.size < 8 else groups.size == 8
        }

        const val DOH_CLOUDFLARE = "https://cloudflare-dns.com/dns-query"
        const val DOH_GOOGLE     = "https://dns.google/dns-query"
        const val DOH_QUAD9      = "https://dns.quad9.net/dns-query"

        /** Published anycast addresses of the built-in providers (literal IPs: no lookup). */
        private val DOH_BOOTSTRAP = mapOf(
            "cloudflare-dns.com" to "1.1.1.1",
            "dns.google" to "8.8.8.8",
            "dns.quad9.net" to "9.9.9.9",
        )
    }
}
