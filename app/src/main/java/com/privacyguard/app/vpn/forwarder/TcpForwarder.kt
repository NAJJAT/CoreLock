package com.privacyguard.vpn.forwarder

import android.util.Log
import com.privacyguard.app.BuildConfig
import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.core.session.SessionTable
import com.privacyguard.core.utils.Checksum
import com.privacyguard.vpn.inspector.EncryptionEnforcer
import com.privacyguard.vpn.interception.TlsInterception
import com.privacyguard.vpn.dualvpn.DualVpnConfig
import com.privacyguard.vpn.dualvpn.DualVpnTunnel
import com.privacyguard.vpn.tunnel.TunWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// FIXED: Removed @Inject because not using Hilt
class TcpForwarder(
    private val sessionTable: SessionTable,
    private val tunWriter: TunWriter,
    private val encEnforcer: EncryptionEnforcer,
    private val filterEngine: com.privacyguard.core.filter.FilterEngine,
    private val protectSocket: (java.net.Socket) -> Boolean,
    // HTTPS payload inspection; TlsInterception.None outside enterprise builds.
    private val interception: TlsInterception,
    private val dualVpnConfig: () -> DualVpnConfig = { DualVpnConfig() },
) : Runnable {

    companion object {
        private const val TAG = "TcpForwarder"
        private const val BUFFER_SIZE = 32_767
        // Max TCP payload per injected segment: TUN MTU (1500) − 20 IP − 20 TCP.
        private const val MSS = 1460
    }

    private val selector = Selector.open()
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    val forwardedBytesIn = AtomicLong(0)
    val forwardedBytesOut = AtomicLong(0)
    val connectingCount = AtomicLong(0)
    val errorCount = AtomicLong(0)
    val encryptionBlocked = AtomicLong(0)
    val mitmInterceptedCount = AtomicLong(0)

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(this, "tcp-selector").also { it.isDaemon = true; it.start() }
        Log.d(TAG, "TCP Forwarder started")
    }

    fun stop() {
        running.set(false)
        selector.wakeup()
        thread?.join(2_000)
        selector.close()
        Log.d(TAG, "TCP Forwarder stopped")
    }

    fun handle(ip: IpPacket, tcp: TcpPacket, ownerUid: Int = -1, ownerPackage: String? = null) {
        val key = SessionKey.of(
            ip.sourceIp, tcp.sourcePort,
            ip.destinationIp, tcp.destinationPort,
            IpPacket.PROTO_TCP,
        )
        Log.d(TAG, "TCP: ${tcp.sourcePort}→${tcp.destinationPort} syn=${tcp.isSyn} ack=${tcp.flagAck} fin=${tcp.isFin} rst=${tcp.isRst} data=${tcp.data.size}")
        when {
            tcp.isSyn && !tcp.flagAck -> handleSyn(ip, tcp, key, ownerUid, ownerPackage)
            tcp.isRst -> {
                Log.d(TAG, "RST for $key")
                sessionTable.remove(key)
            }
            tcp.isFin -> handleFin(ip, tcp, key)
            else -> handleData(ip, tcp, key, ownerUid, ownerPackage)
        }
    }

    private fun handleSyn(ip: IpPacket, tcp: TcpPacket, key: SessionKey, ownerUid: Int, ownerPackage: String?) {
        Log.d(TAG, "SYN: connecting to ${ip.destinationIp}:${tcp.destinationPort}")
        val session = sessionTable.getOrCreate(key, uid = ownerUid, ownerPackage = ownerPackage)
        session.lastDeviceSeq = tcp.sequenceNumber

        val cfg = dualVpnConfig()
        if (cfg.isValid) {
            handleSynDualVpn(ip, tcp, key, session, cfg)
            return
        }

        try {
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            protectSocket(channel.socket())
            channel.connect(InetSocketAddress(ip.destinationIp, tcp.destinationPort))

            session.tcpChannel = channel
            session.tcpState.set(Session.TcpState.SYN_RECEIVED)

            selector.wakeup()
            val selKey = channel.register(selector, SelectionKey.OP_CONNECT, session)
            session.selectionKey = selKey

            connectingCount.incrementAndGet()
        } catch (e: Exception) {
            Log.w(TAG, "SYN error $key: ${e.message}")
            sessionTable.remove(key)
            sendRstToDevice(ip, tcp)
            errorCount.incrementAndGet()
        }
    }

    private fun handleSynDualVpn(
        ip: IpPacket,
        tcp: TcpPacket,
        key: SessionKey,
        session: Session,
        cfg: DualVpnConfig,
    ) {
        connectingCount.incrementAndGet()
        session.tcpState.set(Session.TcpState.SYN_RECEIVED)

        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Open blocking SocketChannel to hop1
                val channel = SocketChannel.open()
                channel.configureBlocking(true)
                protectSocket(channel.socket())
                channel.socket().connect(
                    InetSocketAddress(cfg.firstHopHost, cfg.firstHopPort), 15_000
                )

                // Negotiate full 2-hop SOCKS5 chain through to the real target
                val ins  = channel.socket().getInputStream()
                val outs = channel.socket().getOutputStream()
                DualVpnTunnel.negotiateChain(ins, outs, cfg, ip.destinationIp, tcp.destinationPort)

                // Switch to non-blocking for the selector loop
                channel.configureBlocking(false)
                session.tcpChannel = channel
                session.tcpState.set(Session.TcpState.ESTABLISHED)

                // Register for reads
                selector.wakeup()
                val selKey = channel.register(selector, SelectionKey.OP_READ, session)
                session.selectionKey = selKey

                // Notify device that connection is established
                sendSynAckToDevice(session)
                Log.i(TAG, "Dual VPN tunnel ready: ${cfg.firstHopHost} → ${cfg.secondHopHost} → ${ip.destinationIp}:${tcp.destinationPort}")
            } catch (e: Exception) {
                Log.w(TAG, "Dual VPN SYN failed for $key: ${e.message}")
                sessionTable.remove(key)
                sendRstToDevice(ip, tcp)
                errorCount.incrementAndGet()
            } finally {
                connectingCount.decrementAndGet()
            }
        }
    }



    private fun handleData(ip: IpPacket, tcp: TcpPacket, key: SessionKey, ownerUid: Int, ownerPackage: String?) {
        val session = sessionTable.get(key) ?: run {
            Log.w(TAG, "No session for data packet: $key")
            return
        }

        if (session.ownerUid == -1 && ownerUid >= 0) {
            session.ownerUid = ownerUid
            session.ownerPackage = ownerPackage
        }

        val state = session.tcpState.get()
        if (state != Session.TcpState.ESTABLISHED && state != Session.TcpState.SYN_RECEIVED) {
            Log.d(TAG, "Data ignored — state=$state for $key")
            return
        }
        if (!tcp.hasData) return

        // MITM sessions: drop retransmits of bytes we already ACKed (compare modulo
        // 2^32) so the TLS stream to the local MITM server never sees duplicates.
        if (session.isMitmIntercepted) {
            val end = (tcp.sequenceNumber + tcp.data.size) and 0xFFFFFFFFL
            val behind = (session.lastAckToDevice - end) and 0xFFFFFFFFL
            if (behind < 0x80000000L) {
                Log.d(TAG, "Dropping retransmitted ${tcp.data.size}B for MITM session $key")
                tunWriter.enqueueWithChecksums(
                    buildTcp(
                        srcIp = ip.destinationIp,
                        srcPort = tcp.destinationPort,
                        dstIp = ip.sourceIp,
                        dstPort = tcp.sourcePort,
                        seqNum = session.sendSeq,
                        ackNum = session.lastAckToDevice,
                        ack = true,
                    )
                )
                return
            }
        }

        if (!session.encryptionClassified) {
            // ── Buffer a multi-segment ClientHello before classifying ──────────
            // Chrome's post-quantum ClientHello (~1.6 KB) spans several TCP
            // segments, and the SNI hostname lives past the first segment. Judging
            // on the first segment alone misses the hostname, so MITM never fires
            // and the connection slips through unintercepted. Accumulate the first
            // TLS record, then classify and route on the whole thing.
            var clientHelloBytes = tcp.data
            if (key.destinationPort == 443 && !session.isMitmIntercepted &&
                tcp.data.isNotEmpty() &&
                (session.httpOutBytes.isNotEmpty() || (tcp.data[0].toInt() and 0xFF) == 0x16)) {
                val buffered = session.httpOutBytes + tcp.data
                val recordLen = if (buffered.size >= 5)
                    5 + (((buffered[3].toInt() and 0xFF) shl 8) or (buffered[4].toInt() and 0xFF))
                    else Int.MAX_VALUE
                if (buffered.size < recordLen && buffered.size < 16_384) {
                    session.httpOutBytes = buffered            // keep buffering, ACK, wait
                    sendAckToDevice(ip, tcp, session)
                    return
                }
                clientHelloBytes = buffered
                session.httpOutBytes = ByteArray(0)
            }

            val result = encEnforcer.inspect(ip, tcp)
            // SNI from the FULL record (the first segment's parse may have missed it).
            val sni = result.sniHostname
                ?: com.privacyguard.core.tls.ClientHelloParser.parse(clientHelloBytes)?.sni
            session.encryptionStatus = result.encryptionStatus
            session.tlsVersion = result.tlsVersion
            session.encryptionClassified = true
            if (sni != null) session.tlsSni = sni

            // DoH endpoints carry DNS past the blocklist; reset so the app falls back
            // to plain DNS through the tunnel.
            if (com.privacyguard.vpn.firewall.DnsBypassGuard.isDohHost(sni)) {
                encryptionBlocked.incrementAndGet()
                sendRstToDevice(ip, tcp)
                runCatching { session.tcpChannel?.close() }
                sessionTable.remove(key)
                return
            }

            // ── MITM redirect ─────────────────────────────────────────────────
            // First TLS ClientHello on port 443: redirect the session to the local
            // MITM SSL server instead of the real destination. The MITM engine
            // completes TLS with the device using a forged cert and opens the real
            // upstream connection itself, passing plaintext to TlsInterception.capture.
            if (sni != null &&
                key.destinationPort == 443 &&
                !session.isMitmIntercepted &&
                interception.shouldIntercept(session.ownerPackage, sni)) {

                val clientHello = clientHelloBytes.copyOf()
                // Bind the loopback client socket first so MitmEngine can accept
                // exactly this peer and reject any other local app that finds the port.
                val mitmChannel = try {
                    SocketChannel.open().apply {
                        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MITM loopback bind failed: ${e.message}")
                    null
                }
                val clientPort = (mitmChannel?.localAddress as? InetSocketAddress)?.port ?: -1
                val mitmPort = if (mitmChannel == null) -1 else interception.intercept(session, clientPort)
                if (mitmPort <= 0) runCatching { mitmChannel?.close() }

                if (mitmPort > 0 && mitmChannel != null) {
                    session.pendingMitmData = clientHello
                    session.isMitmIntercepted = true

                    // Close the existing upstream channel (no TLS data exchanged yet —
                    // only the TCP handshake), then reconnect to the local MITM port.
                    session.selectionKey?.cancel()
                    runCatching { session.tcpChannel?.close() }

                    try {
                        mitmChannel.configureBlocking(false)
                        // Loopback is local — do NOT protect() this socket.
                        mitmChannel.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), mitmPort))
                        session.tcpChannel = mitmChannel
                        selector.wakeup()
                        session.selectionKey = mitmChannel.register(selector, SelectionKey.OP_CONNECT, session)
                        connectingCount.incrementAndGet()
                    } catch (e: Exception) {
                        Log.w(TAG, "MITM redirect failed for $sni: ${e.message}")
                        runCatching { mitmChannel.close() }
                        session.isMitmIntercepted = false
                        session.pendingMitmData = null
                        interception.markPinned(sni)
                    }
                    // ACK the ClientHello now: it will be replayed to the MITM socket.
                    // Without this the device retransmits it and the MITM server
                    // receives a second ClientHello mid-handshake.
                    sendAckToDevice(ip, tcp, session)
                    return  // Do NOT forward raw ClientHello to original upstream
                }
            }
            // ─────────────────────────────────────────────────────────────────

            if (result.encryptionStatus == EncryptionStatus.CLEARTEXT ||
                result.encryptionStatus == EncryptionStatus.WEAK_TLS) {
                val decision = filterEngine.evaluate(
                    uid = session.ownerUid,
                    pkg = session.ownerPackage,
                    domain = session.hostname,
                    ip = key.destinationIp,
                    port = key.destinationPort,
                    protocol = IpPacket.PROTO_TCP,
                    encStatus = result.encryptionStatus,
                    isBackground = session.wasBackground,
                )
                if (decision.isBlocked) {
                    Log.d(TAG, "Blocked cleartext: ${key.destinationIp}:${key.destinationPort}")
                    encryptionBlocked.incrementAndGet()
                    sendRstToDevice(ip, tcp)
                    sessionTable.remove(key)
                    return
                }
            }

            // If a multi-segment ClientHello was buffered but NOT sent to MITM
            // (pinned, MITM off, or intercept failed), forward the whole buffer to
            // the real server — the generic path below would send only the last
            // segment and drop the earlier bytes.
            if (clientHelloBytes.size != tcp.data.size) {
                val channel = session.tcpChannel
                if (channel != null) {
                    try {
                        val bb = ByteBuffer.wrap(clientHelloBytes)
                        while (bb.hasRemaining()) { if (channel.write(bb) <= 0) break }
                        session.recordOutbound(clientHelloBytes.size)
                        forwardedBytesOut.addAndGet(clientHelloBytes.size.toLong())
                        sendAckToDevice(ip, tcp, session)
                    } catch (e: Exception) {
                        Log.w(TAG, "Forward buffered ClientHello failed for $key: ${e.message}")
                        sessionTable.remove(key)
                        sendRstToDevice(ip, tcp)
                    }
                }
                return
            }
        }

        // ── PAYLOAD CAPTURE (fully async — never touches the forwarding path) ──
        if (interception.isEnabled && !isPrivacyGuardTraffic(session.ownerPackage)) {
            val snap = tcp.data.copyOf()          // snapshot before forwarding
            val enc  = session.encryptionStatus
            // Metadata-only stub for TLS sessions we are NOT decrypting. MITM'd sessions
            // log real decrypted entries via capture — a stub there would show
            // up as a bogus "encrypted" entry next to the plaintext.
            val needStub = session.key.destinationPort == 443 && session.tlsSni != null &&
                !session.metadataLogged && !session.isMitmIntercepted
            if (needStub) session.metadataLogged = true   // set flag on capture thread

            coroutineScope.launch {
                try {
                    if (needStub) interception.logNotDecrypted(session, snap.size)
                    if (enc == EncryptionStatus.CLEARTEXT) {
                        interception.capture("OUTBOUND", snap, session)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Outbound capture failed: ${e.message}")
                }
            }
        }

        // ── FORWARD — always runs, isolated from capture ──────────────────────
        val channel = session.tcpChannel ?: run {
            Log.w(TAG, "No channel for data packet: $key")
            return
        }
        try {
            val buf = ByteBuffer.wrap(tcp.data)
            while (buf.hasRemaining()) {
                val written = channel.write(buf)
                if (written <= 0) break   // Non-blocking: socket buffer full, stop spinning
            }
            session.recordOutbound(tcp.data.size)
            forwardedBytesOut.addAndGet(tcp.data.size.toLong())
            sendAckToDevice(ip, tcp, session)
        } catch (e: Exception) {
            Log.w(TAG, "Forward failed for $key: ${e.message}")
            sessionTable.remove(key)
            sendRstToDevice(ip, tcp)
            errorCount.incrementAndGet()
        }
    }


    private fun isPrivacyGuardTraffic(ownerPackage: String?): Boolean {
        return ownerPackage == BuildConfig.APPLICATION_ID ||
                ownerPackage?.startsWith("com.privacyguard.app") == true
    }

    private fun handleFin(ip: IpPacket, tcp: TcpPacket, key: SessionKey) {
        Log.d(TAG, "FIN for $key")
        val session = sessionTable.get(key) ?: return
        session.tcpState.set(Session.TcpState.FIN_WAIT)
        runCatching { session.tcpChannel?.shutdownOutput() }
        sendFinAckToDevice(ip, tcp, session)
    }

    override fun run() {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        Log.d(TAG, "Selector loop started")
        while (running.get()) {
            try {
                if (selector.select(100L) == 0) continue
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val selKey = keys.next()
                    keys.remove()
                    val session = selKey.attachment() as? Session ?: continue
                    try {
                        when {
                            selKey.isConnectable -> finishConnect(selKey, session)
                            selKey.isReadable -> readFromRemote(selKey, session, buffer)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Selector error for ${session.key}: ${e.message}")
                        sessionTable.remove(session.key)
                        errorCount.incrementAndGet()
                    }
                }
            } catch (e: Exception) {
                if (!running.get()) break
                Log.e(TAG, "Selector loop error: ${e.message}")
            }
        }
        Log.d(TAG, "Selector loop exited")
    }

    private fun finishConnect(selKey: SelectionKey, session: Session) {
        val channel = selKey.channel() as SocketChannel
        try {
            if (channel.finishConnect()) {
                connectingCount.decrementAndGet()
                session.tcpState.set(Session.TcpState.ESTABLISHED)
                selKey.interestOps(SelectionKey.OP_READ)

                val pending = session.pendingMitmData
                if (pending != null) {
                    // This is a MITM redirect connect — replay the ClientHello into the
                    // local MITM server socket. Do NOT send another SYN-ACK; the device
                    // already received one when the original channel connected.
                    session.pendingMitmData = null
                    try {
                        val buf = ByteBuffer.wrap(pending)
                        while (buf.hasRemaining()) {
                            val n = channel.write(buf)
                            if (n <= 0) break
                        }
                        Log.d(TAG, "Replayed ${pending.size}B ClientHello to MITM port")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to replay MITM ClientHello: ${e.message}")
                    }
                } else {
                    // Normal initial connection — send SYN-ACK to device.
                    sendSynAckToDevice(session)
                    Log.d(TAG, "Connected → SYN-ACK sent for ${session.key}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "finishConnect failed for ${session.key}: ${e.message}")
            sessionTable.remove(session.key)
            errorCount.incrementAndGet()
        }
    }

    private fun readFromRemote(selKey: SelectionKey, session: Session, buffer: ByteBuffer) {
        val channel = selKey.channel() as SocketChannel
        buffer.clear()
        val n = channel.read(buffer)
        if (n < 0) {
            sessionTable.remove(session.key)
            return
        }
        if (n == 0) return
        buffer.flip()
        val data = ByteArray(n).also { buffer.get(it) }
        session.recordInbound(n)
        forwardedBytesIn.addAndGet(n.toLong())

        // Capture response async — injectDataToDevice always runs regardless
        if (interception.isEnabled &&
            session.encryptionStatus == EncryptionStatus.CLEARTEXT) {
            val snap = data.copyOf()
            coroutineScope.launch {
                try { interception.capture("INBOUND", snap, session) }
                catch (e: Exception) { Log.w(TAG, "Inbound capture failed: ${e.message}") }
            }
        }

        injectDataToDevice(session, data)   // always executes
        Log.d(TAG, "Read $n bytes from server → injected to device")
    }

    private fun sendSynAckToDevice(session: Session) {
        val pkt = buildTcp(
            srcIp = session.key.destinationIp,
            srcPort = session.key.destinationPort,
            dstIp = session.key.sourceIp,
            dstPort = session.key.sourcePort,
            seqNum = session.sendSeq,
            ackNum = session.lastDeviceSeq + 1,
            syn = true,
            ack = true,
        )
        session.sendSeq++
        session.lastAckToDevice = session.lastDeviceSeq + 1
        tunWriter.enqueueWithChecksums(pkt)
    }

    private fun sendAckToDevice(ip: IpPacket, tcp: TcpPacket, session: Session) {
        val expected = tcp.sequenceNumber + tcp.data.size
        if (expected <= session.lastAckToDevice) return
        session.lastAckToDevice = expected
        tunWriter.enqueueWithChecksums(
            buildTcp(
                srcIp = ip.destinationIp,
                srcPort = tcp.destinationPort,
                dstIp = ip.sourceIp,
                dstPort = tcp.sourcePort,
                seqNum = session.sendSeq,
                ackNum = expected,
                ack = true,
            )
        )
    }

    private fun injectDataToDevice(session: Session, data: ByteArray) {
        // The TUN MTU is 1500, so a single injected IP packet must stay within it
        // (1500 - 20 IP - 20 TCP = 1460 bytes of payload). A larger server flight —
        // e.g. a TLS ServerHello carrying the certificate — must be split into
        // MSS-sized TCP segments, or the device silently drops the oversized packet
        // and keeps retransmitting, so the TLS handshake never completes.
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + MSS, data.size)
            val chunk = data.copyOfRange(offset, end)
            tunWriter.enqueueWithChecksums(
                buildTcp(
                    srcIp = session.key.destinationIp,
                    srcPort = session.key.destinationPort,
                    dstIp = session.key.sourceIp,
                    dstPort = session.key.sourcePort,
                    seqNum = session.sendSeq,
                    ackNum = session.lastAckToDevice,
                    ack = true,
                    psh = end == data.size,   // PSH only on the final segment
                    data = chunk,
                )
            )
            session.sendSeq += chunk.size
            offset = end
        }
    }

    private fun sendFinAckToDevice(ip: IpPacket, tcp: TcpPacket, session: Session) {
        tunWriter.enqueueWithChecksums(
            buildTcp(
                srcIp = ip.destinationIp,
                srcPort = tcp.destinationPort,
                dstIp = ip.sourceIp,
                dstPort = tcp.sourcePort,
                seqNum = session.sendSeq,
                ackNum = tcp.sequenceNumber + 1,
                ack = true,
                fin = true,
            )
        )
        session.sendSeq++
        sessionTable.remove(session.key)
    }

    private fun sendRstToDevice(ip: IpPacket, tcp: TcpPacket) {
        tunWriter.enqueueWithChecksums(
            buildTcp(
                srcIp = ip.destinationIp,
                srcPort = tcp.destinationPort,
                dstIp = ip.sourceIp,
                dstPort = tcp.sourcePort,
                seqNum = tcp.acknowledgmentNumber,
                ackNum = 0L,
                rst = true,
            )
        )
    }

    @Suppress("LongParameterList")
    private fun buildTcp(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        syn: Boolean = false,
        ack: Boolean = false,
        psh: Boolean = false,
        fin: Boolean = false,
        rst: Boolean = false,
        data: ByteArray = ByteArray(0),
    ): ByteArray {
        val totalLen = 40 + data.size
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x45.toByte()
        pkt[1] = 0x00
        pkt[2] = (totalLen ushr 8).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        pkt[4] = 0x00
        pkt[5] = 0x00
        pkt[6] = 0x40.toByte()
        pkt[7] = 0x00
        pkt[8] = 64
        pkt[9] = 6
        srcIp.split('.').forEachIndexed { i, s -> pkt[12 + i] = s.toInt().toByte() }
        dstIp.split('.').forEachIndexed { i, s -> pkt[16 + i] = s.toInt().toByte() }

        pkt[20] = (srcPort ushr 8).toByte()
        pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = (dstPort ushr 8).toByte()
        pkt[23] = (dstPort and 0xFF).toByte()

        fun writeLong(off: Int, v: Long) {
            pkt[off] = ((v ushr 24) and 0xFF).toByte()
            pkt[off + 1] = ((v ushr 16) and 0xFF).toByte()
            pkt[off + 2] = ((v ushr 8) and 0xFF).toByte()
            pkt[off + 3] = (v and 0xFF).toByte()
        }
        writeLong(24, seqNum)
        writeLong(28, ackNum)

        pkt[32] = 0x50.toByte()
        pkt[33] = ((if (syn) 0x02 else 0) or
                (if (ack) 0x10 else 0) or
                (if (psh) 0x08 else 0) or
                (if (fin) 0x01 else 0) or
                (if (rst) 0x04 else 0)).toByte()
        pkt[34] = 0xFF.toByte()
        pkt[35] = 0xFF.toByte()
        pkt[36] = 0
        pkt[37] = 0
        pkt[38] = 0
        pkt[39] = 0

        if (data.isNotEmpty()) data.copyInto(pkt, 40)
        Checksum.setIpv4HeaderChecksum(pkt, 0)
        Checksum.setTcpChecksum(pkt, 0)
        return pkt
    }
}
