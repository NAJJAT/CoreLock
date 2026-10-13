package com.privacyguard.vpn.forwarder

import android.util.Log
import com.privacyguard.app.BuildConfig
import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.Ipv6Packet
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.session.OutSegment
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Terminates the device's TCP connections (IPv4 and IPv6) and relays them over
 * real, protected sockets.
 *
 * Device → server: each segment is forwarded once, in order, and ACKed only once
 * the upstream socket took it (or queued it, bounded by [MAX_PENDING_OUT]).
 *
 * Server → device: bytes are read from the socket only while the device's
 * advertised window and the TUN queue have room for them, and every injected
 * segment is kept until the device ACKs it, then resent on three duplicate ACKs
 * or a retransmission timeout. (Reading without limits and dropping on a full
 * TUN queue used to lose segments for good, which showed up as TLS
 * BAD_RECORD_MAC errors and stalled downloads.)
 *
 * Threads: [handle] runs on the TUN reader thread; socket I/O, injection and
 * timers run on the selector thread.
 */
class TcpForwarder(
    private val sessionTable: SessionTable,
    private val tunWriter: TunWriter,
    private val encEnforcer: EncryptionEnforcer,
    private val filterEngine: com.privacyguard.core.filter.FilterEngine,
    private val protectSocket: (java.net.Socket) -> Boolean,
    // HTTPS payload inspection; TlsInterception.None outside enterprise builds.
    private val interception: TlsInterception,
    private val dualVpnConfig: () -> DualVpnConfig = { DualVpnConfig() },
    // Gets the whole first TLS record, reassembled across segments, once per session.
    private val onClientHello: ((Session, ByteArray) -> Unit)? = null,
) : Runnable {

    companion object {
        private const val TAG = "TcpForwarder"
        private const val BUFFER_SIZE = 32_767
        // Max TCP payload per injected segment: TUN MTU (1500) − IP header − 20 TCP.
        private const val MSS_V4 = 1460
        private const val MSS_V6 = 1440
        private const val SEQ_MASK = 0xFFFFFFFFL
        private const val HALF_SEQ = 0x80000000L
        // Device bytes ACKed but not yet taken by a full upstream socket, per session.
        private const val MAX_PENDING_OUT = 256 * 1024
        private const val SELECT_TIMEOUT_MS = 50L
        // TUN queue slots kept free for ACKs, DNS answers and other sessions.
        private const val TUN_RESERVE = 64
        private const val INJECT_TIMEOUT_MS = 200L
        private const val TIMER_INTERVAL_MS = 100L
        private const val MAX_RTO_MS = 16_000L
        private const val MAX_RETRANSMITS = 8
        private const val PROBE_INTERVAL_MS = 1_000L

        /** True when [ack] is at or past [seq], modulo 2^32. */
        internal fun seqAtOrAfter(ack: Long, seq: Long): Boolean = ((ack - seq) and SEQ_MASK) < HALF_SEQ
    }

    private val selector = Selector.open()
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val forwardedBytesIn = AtomicLong(0)
    val forwardedBytesOut = AtomicLong(0)
    val connectingCount = AtomicLong(0)
    val errorCount = AtomicLong(0)
    val encryptionBlocked = AtomicLong(0)
    val mitmInterceptedCount = AtomicLong(0)
    val retransmittedSegments = AtomicLong(0)

    /** Sessions with server→device data the device has not ACKed yet (timer work). */
    private val unackedSessions = ConcurrentHashMap.newKeySet<Session>()
    /** Sessions whose socket reads are paused, with the time they were paused. */
    private val pausedSessions = ConcurrentHashMap<Session, Long>()
    private var lastTimerRun = 0L

    init {
        tunWriter.onDrained = { if (pausedSessions.isNotEmpty()) selector.wakeup() }
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(this, "tcp-selector").also { it.isDaemon = true; it.start() }
        Log.d(TAG, "TCP Forwarder started")
    }

    fun stop() {
        running.set(false)
        selector.wakeup()
        thread?.join(2_000)
        coroutineScope.cancel()
        selector.close()
        unackedSessions.clear()
        pausedSessions.clear()
        Log.d(TAG, "TCP Forwarder stopped")
    }

    fun handle(ip: IpPacket, tcp: TcpPacket, ownerUid: Int = -1, ownerPackage: String? = null) =
        handle(SessionKey.of(ip.sourceIp, tcp.sourcePort, ip.destinationIp, tcp.destinationPort, IpPacket.PROTO_TCP),
            tcp, ownerUid, ownerPackage)

    fun handleIpv6(ipv6: Ipv6Packet, tcp: TcpPacket, ownerUid: Int = -1, ownerPackage: String? = null) =
        handle(SessionKey.of(ipv6.sourceIp, tcp.sourcePort, ipv6.destinationIp, tcp.destinationPort, IpPacket.PROTO_TCP),
            tcp, ownerUid, ownerPackage)

    /** One device→server segment for the connection [key] (TUN reader thread). */
    fun handle(key: SessionKey, tcp: TcpPacket, ownerUid: Int = -1, ownerPackage: String? = null) {
        when {
            tcp.isSyn -> handleSyn(key, tcp, ownerUid, ownerPackage)
            tcp.isRst -> sessionTable.remove(key)
            else -> {
                val session = sessionTable.get(key)
                if (session == null) {
                    // Data for a connection we do not know (VPN restarted, or we reset
                    // it): refuse it now rather than let the app wait for a timeout.
                    if (tcp.hasData || tcp.isFin) sendRst(key, tcp)
                    return
                }
                if (session.ownerUid == -1 && ownerUid >= 0) {
                    session.ownerUid = ownerUid
                    session.ownerPackage = ownerPackage
                }
                onDeviceAck(session, tcp)
                if (tcp.hasData) handleData(session, tcp)
                if (tcp.isFin) handleFin(session, tcp)
            }
        }
    }

    /** Resets [key]'s connection toward the device and forgets it (e.g. a rule now blocks it). */
    /** Ends a live session because a rule now blocks it. */
    fun reset(key: SessionKey) {
        val session = sessionTable.get(key) ?: return
        session.blocked = true
        resetSession(session)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleSyn(key: SessionKey, tcp: TcpPacket, ownerUid: Int, ownerPackage: String?) {
        sessionTable.get(key)?.let { existing ->
            if (existing.lastDeviceSeq == tcp.sequenceNumber) {
                // Same SYN again (retransmitted). Still connecting: the connect path
                // answers it; a second channel or SOCKS chain here broke the session.
                // Already answered: our SYN-ACK crossed it, so send it again.
                if (existing.synAckSeq >= 0) resendSynAckToDevice(existing)
                return
            }
            // A different initial sequence number: the port was reused. Start over.
            sessionTable.remove(key)
        }
        val session = sessionTable.getOrCreate(key, uid = ownerUid, ownerPackage = ownerPackage)
        session.lastDeviceSeq = tcp.sequenceNumber
        session.deviceWindow = tcp.windowSize

        val cfg = dualVpnConfig()
        if (cfg.isValid) {
            handleSynDualVpn(key, tcp, session, cfg)
            return
        }

        var channel: SocketChannel? = null
        try {
            channel = SocketChannel.open()
            channel.configureBlocking(false)
            // An unprotected socket would be routed back into this tunnel, forever.
            if (!protectSocket(channel.socket())) throw IOException("protect() failed")
            channel.connect(InetSocketAddress(key.destinationIp, key.destinationPort))

            session.tcpChannel = channel
            session.tcpState.set(Session.TcpState.SYN_RECEIVED)

            registerOnSelector(channel, SelectionKey.OP_CONNECT, session)

            connectingCount.incrementAndGet()
        } catch (e: Exception) {
            Log.w(TAG, "SYN error $key: ${e.message}")
            runCatching { channel?.close() }
            sessionTable.remove(key)
            sendRst(key, tcp)
            errorCount.incrementAndGet()
        }
    }

    private fun handleSynDualVpn(
        key: SessionKey,
        tcp: TcpPacket,
        session: Session,
        cfg: DualVpnConfig,
    ) {
        connectingCount.incrementAndGet()
        session.tcpState.set(Session.TcpState.SYN_RECEIVED)

        coroutineScope.launch {
            var channel: SocketChannel? = null
            try {
                // Open blocking SocketChannel to hop1
                channel = SocketChannel.open()
                channel.configureBlocking(true)
                if (!protectSocket(channel.socket())) throw IOException("protect() failed")
                channel.socket().connect(
                    InetSocketAddress(cfg.firstHopHost, cfg.firstHopPort), 15_000
                )

                // Negotiate full 2-hop SOCKS5 chain through to the real target
                val ins  = channel.socket().getInputStream()
                val outs = channel.socket().getOutputStream()
                DualVpnTunnel.negotiateChain(ins, outs, cfg, key.destinationIp, key.destinationPort)

                // Switch to non-blocking for the selector loop
                channel.configureBlocking(false)
                session.tcpChannel = channel
                session.tcpState.set(Session.TcpState.ESTABLISHED)

                // Register for reads, then tell the device the connection is up.
                registerOnSelector(channel, SelectionKey.OP_READ, session) {
                    sendSynAckToDevice(session)
                    Log.d(TAG, "Dual VPN tunnel ready for ${session.key}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Dual VPN SYN failed for $key: ${e.message}")
                runCatching { channel?.close() }
                sessionTable.remove(key)
                sendRst(key, tcp)
                errorCount.incrementAndGet()
            } finally {
                connectingCount.decrementAndGet()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Device → server
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleData(session: Session, tcp: TcpPacket) {
        val key = session.key
        val state = session.tcpState.get()
        // CLOSE_WAIT: the server closed its side, but the app may still send.
        if (state != Session.TcpState.ESTABLISHED && state != Session.TcpState.SYN_RECEIVED &&
            state != Session.TcpState.CLOSE_WAIT) {
            Log.d(TAG, "Data ignored — state=$state for $key")
            return
        }

        // Forward every byte exactly once and in order (sequence numbers compared
        // modulo 2^32). Bytes before lastAckToDevice were already forwarded: a
        // retransmit, or the start of a segment the socket only partly took. A segment
        // past it means an earlier one was not fully written and the device will
        // resend from lastAckToDevice.
        val alreadyAcked = (session.lastAckToDevice - tcp.sequenceNumber) and SEQ_MASK
        val payload: ByteArray = when {
            alreadyAcked == 0L -> tcp.data
            alreadyAcked < HALF_SEQ && alreadyAcked < tcp.data.size ->
                tcp.data.copyOfRange(alreadyAcked.toInt(), tcp.data.size)
            else -> {
                Log.d(TAG, "Dropping out-of-window ${tcp.data.size}B for $key")
                sendControlToDevice(session)
                return
            }
        }

        if (!session.encryptionClassified) {
            if (classifyFirstData(session, payload)) return
        }

        // ── PAYLOAD CAPTURE (queued; never blocks forwarding) ─────────────────
        if (interception.isEnabled && !isPrivacyGuardTraffic(session.ownerPackage)) {
            // Metadata-only stub for TLS sessions we are NOT decrypting. MITM'd sessions
            // log real decrypted entries via capture.
            if (key.destinationPort == 443 && session.tlsSni != null &&
                !session.metadataLogged && !session.isMitmIntercepted) {
                session.metadataLogged = true
                runCatching { interception.logNotDecrypted(session, payload.size) }
            }
            if (session.encryptionStatus == EncryptionStatus.CLEARTEXT) {
                runCatching { interception.capture("OUTBOUND", payload, session) }
                    .onFailure { Log.w(TAG, "Outbound capture failed: ${it.message}") }
            }
        }

        val channel = session.tcpChannel ?: run {
            Log.w(TAG, "No channel for data packet: $key")
            return
        }
        try {
            // Written now or queued until the socket drains; ACKed either way. Only
            // a full queue leaves it un-ACKed, and the device then resends it.
            if (!forwardToRemote(session, channel, payload)) return
            session.recordOutbound(payload.size)
            forwardedBytesOut.addAndGet(payload.size.toLong())
            sendAckToDevice(session, payload.size)
        } catch (e: Exception) {
            Log.w(TAG, "Forward failed for $key: ${e.message}")
            resetSession(session)
            errorCount.incrementAndGet()
        }
    }

    /**
     * Classifies the session from its first client bytes and handles everything
     * that depends on them (ClientHello reassembly, DoH reset, MITM redirect,
     * cleartext blocking). Returns true when [payload] has been fully handled.
     */
    private fun classifyFirstData(session: Session, payload: ByteArray): Boolean {
        val key = session.key
        // ── Buffer a multi-segment ClientHello before classifying ──────────
        // Chrome's post-quantum ClientHello (~1.6 KB) spans several TCP segments
        // and the SNI lives past the first one. Accumulate the first TLS record,
        // then classify and route on the whole thing.
        var clientHelloBytes = payload
        if (key.destinationPort == 443 && !session.isMitmIntercepted && payload.isNotEmpty() &&
            (session.clientHelloBuf.isNotEmpty() || (payload[0].toInt() and 0xFF) == 0x16)) {
            val buffered = session.clientHelloBuf + payload
            val recordLen = if (buffered.size >= 5)
                5 + (((buffered[3].toInt() and 0xFF) shl 8) or (buffered[4].toInt() and 0xFF))
                else Int.MAX_VALUE
            if (buffered.size < recordLen && buffered.size < 16_384) {
                session.clientHelloBuf = buffered            // keep buffering, ACK, wait
                sendAckToDevice(session, payload.size)
                return true
            }
            clientHelloBytes = buffered
            session.clientHelloBuf = ByteArray(0)
        }
        if (key.destinationPort == 443 && clientHelloBytes.isNotEmpty() &&
            (clientHelloBytes[0].toInt() and 0xFF) == 0x16) {
            runCatching { onClientHello?.invoke(session, clientHelloBytes) }
                .onFailure { Log.w(TAG, "ClientHello listener failed: ${it.message}") }
        }

        // Classified on the whole record: the last segment alone has neither the
        // record version nor supported_versions, so TLS 1.3 showed up as plain "TLS".
        val result = encEnforcer.inspect(key.destinationPort, clientHelloBytes)
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
            session.blocked = true
            resetSession(session)
            return true
        }

        if (sni != null && key.destinationPort == 443 && !session.isMitmIntercepted &&
            interception.shouldIntercept(session.ownerPackage, sni) &&
            redirectToMitm(session, clientHelloBytes, sni)) {
            sendAckToDevice(session, payload.size)
            return true
        }

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
                session.blocked = true
                resetSession(session)
                return true
            }
        }

        // A multi-segment ClientHello that was buffered but not redirected goes to
        // the real server whole; the generic path would send only the last segment.
        if (clientHelloBytes.size != payload.size) {
            val channel = session.tcpChannel ?: return true
            try {
                // Earlier segments were ACKed already and cannot be resent, so the
                // send queue (empty this early) must take the whole buffer.
                if (!forwardToRemote(session, channel, clientHelloBytes)) {
                    throw IOException("send queue full for buffered ClientHello")
                }
                session.recordOutbound(clientHelloBytes.size)
                forwardedBytesOut.addAndGet(clientHelloBytes.size.toLong())
                sendAckToDevice(session, payload.size)
            } catch (e: Exception) {
                Log.w(TAG, "Forward buffered ClientHello failed for $key: ${e.message}")
                resetSession(session)
            }
            return true
        }
        return false
    }

    /**
     * Moves [session] from the real server to a local MITM server. The upstream
     * socket has only done the TCP handshake, so it is closed and the ClientHello
     * is queued for the new channel. Forging the certificate (a Keystore signature)
     * is slow, so it runs off this thread; the channel connects once it is done.
     * Returns false if the redirect could not even start (the session then goes on
     * to the real server unchanged).
     */
    private fun redirectToMitm(session: Session, clientHello: ByteArray, sni: String): Boolean {
        // Bind the loopback client socket first so MitmEngine can accept exactly
        // this peer and reject any other local app that finds the port.
        val mitmChannel = try {
            SocketChannel.open().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
        } catch (e: Exception) {
            Log.w(TAG, "MITM loopback bind failed: ${e.message}")
            return false
        }
        val clientPort = (mitmChannel.localAddress as? InetSocketAddress)?.port ?: run {
            runCatching { mitmChannel.close() }
            return false
        }

        session.isMitmIntercepted = true
        session.mitmRedirect = true
        session.selectionKey?.cancel()
        runCatching { session.tcpChannel?.close() }
        session.selectionKey = null
        session.tcpChannel = mitmChannel
        synchronized(session.pendingOut) {
            session.pendingOut.clear()
            session.pendingOut.addLast(ByteBuffer.wrap(clientHello.copyOf()))
            session.pendingOutBytes = clientHello.size
        }
        mitmInterceptedCount.incrementAndGet()

        coroutineScope.launch {
            val mitmPort = runCatching { interception.intercept(session, clientPort) }.getOrDefault(-1)
            pendingRegistrations.add(Runnable { connectMitm(session, mitmChannel, mitmPort, sni) })
            selector.wakeup()
        }
        return true
    }

    /** Selector thread: connects the redirected session to the MITM server, or gives up on it. */
    private fun connectMitm(session: Session, mitmChannel: SocketChannel, mitmPort: Int, sni: String) {
        if (session.isClosed) {
            runCatching { mitmChannel.close() }
            return
        }
        try {
            if (mitmPort <= 0) throw IOException("MITM server unavailable")
            mitmChannel.configureBlocking(false)
            // Loopback is local — do NOT protect() this socket.
            mitmChannel.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), mitmPort))
            session.selectionKey = mitmChannel.register(selector, SelectionKey.OP_CONNECT, session)
            connectingCount.incrementAndGet()
        } catch (e: Exception) {
            Log.w(TAG, "MITM redirect failed: ${e.message}")
            runCatching { mitmChannel.close() }
            // The real upstream is gone, so this connection cannot continue. Pass the
            // host through from now on so the app's retry is not intercepted again.
            interception.markPinned(sni)
            resetSession(session)
        }
    }

    private fun isPrivacyGuardTraffic(ownerPackage: String?): Boolean {
        return ownerPackage == BuildConfig.APPLICATION_ID ||
                ownerPackage?.startsWith("com.privacyguard.app") == true
    }

    /**
     * Device FIN = half-close: the app is done sending but still reads. The FIN is
     * ACKed, the socket's output is shut once queued data drains, and the session
     * lives until the server closes too.
     */
    private fun handleFin(session: Session, tcp: TcpPacket) {
        val key = session.key
        Log.d(TAG, "FIN for $key")
        val finSeq = (tcp.sequenceNumber + tcp.data.size) and SEQ_MASK
        if (session.deviceFinReceived) {
            // Retransmitted FIN: our ACK was lost.
            if (((finSeq + 1) and SEQ_MASK) == session.lastAckToDevice) sendControlToDevice(session)
            return
        }
        // Only once every byte before the FIN was forwarded; otherwise the device
        // resends the missing data together with the FIN.
        if (finSeq != session.lastAckToDevice) return
        session.deviceFinReceived = true
        session.lastAckToDevice = (finSeq + 1) and SEQ_MASK   // a FIN takes one sequence number

        if (session.remoteClosed) {
            // The server closed first and our FIN is out: both sides are done.
            sendControlToDevice(session)
            sessionTable.remove(key)
            return
        }
        session.tcpState.set(Session.TcpState.FIN_WAIT)
        synchronized(session.pendingOut) {
            if (session.pendingOut.isEmpty()) runCatching { session.tcpChannel?.shutdownOutput() }
        }
        sendControlToDevice(session)
    }

    /**
     * Hands device bytes to the upstream socket: written now, or queued when the
     * socket is full or still connecting (flushed on OP_WRITE). Returns false only
     * when the queue is at its cap; the caller then does not ACK, so the device
     * resends later. Runs on the TUN reader thread.
     */
    private fun forwardToRemote(session: Session, channel: SocketChannel, data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        synchronized(session.pendingOut) {
            if (session.pendingOut.isEmpty() && channel.isConnected) {
                val bb = ByteBuffer.wrap(data)
                while (bb.hasRemaining()) { if (channel.write(bb) <= 0) break }
                if (!bb.hasRemaining()) return true
                session.pendingOut.addLast(bb)
                session.pendingOutBytes += bb.remaining()
            } else {
                if (session.pendingOutBytes + data.size > MAX_PENDING_OUT) return false
                session.pendingOut.addLast(ByteBuffer.wrap(data))
                session.pendingOutBytes += data.size
            }
            // A connecting channel gets OP_WRITE from finishConnect instead.
            if (channel.isConnected) setWriteInterest(session)
            return true
        }
    }

    private fun setWriteInterest(session: Session) {
        val key = session.selectionKey ?: return
        runCatching {
            val ops = key.interestOps()
            if (ops and SelectionKey.OP_WRITE == 0) {
                key.interestOps(ops or SelectionKey.OP_WRITE)
                selector.wakeup()
            }
        }
    }

    /** Selector thread: drains the send queue as the socket accepts more. */
    private fun flushPending(selKey: SelectionKey, session: Session) {
        val channel = selKey.channel() as SocketChannel
        val windowBefore = advertisedWindow(session)
        synchronized(session.pendingOut) {
            while (session.pendingOut.isNotEmpty()) {
                val head = session.pendingOut.peekFirst() ?: break
                val before = head.remaining()
                channel.write(head)
                session.pendingOutBytes -= before - head.remaining()
                if (head.hasRemaining()) break
                session.pendingOut.removeFirst()
            }
            if (session.pendingOut.isEmpty()) {
                selKey.interestOps(selKey.interestOps() and SelectionKey.OP_WRITE.inv())
                if (session.deviceFinReceived) runCatching { channel.shutdownOutput() }
            }
        }
        // The device was told the window was (nearly) closed; tell it it reopened
        // rather than leave it waiting for its own probe timer.
        if (windowBefore < MSS_V4 * 4 && advertisedWindow(session) > windowBefore) {
            sendControlToDevice(session)
        }
    }

    /** Receive window to advertise: what the send queue can still take, max 64 KB (no scaling). */
    private fun advertisedWindow(session: Session): Int =
        (MAX_PENDING_OUT - session.pendingOutBytes).coerceIn(0, 0xFFFF)

    // ─────────────────────────────────────────────────────────────────────────
    // Server → device
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Processes the device's ACK number and window (TUN reader thread): frees
     * acknowledged segments, fast-retransmits on the third duplicate ACK, and
     * wakes the selector if reading was paused for lack of window.
     */
    private fun onDeviceAck(session: Session, tcp: TcpPacket) {
        if (!tcp.flagAck || session.synAckSeq < 0) return
        val ack = tcp.acknowledgmentNumber
        var resend: OutSegment? = null
        synchronized(session.unackedToDevice) {
            val queue = session.unackedToDevice
            // An ACK for data we never sent is bogus.
            val beyond = (ack - (session.sendSeq and SEQ_MASK)) and SEQ_MASK
            if (beyond in 1 until HALF_SEQ) return
            val prev = session.deviceAckedSeq
            val advanced = prev < 0 || ((ack - prev) and SEQ_MASK) in 1 until HALF_SEQ
            if (advanced) {
                session.deviceAckedSeq = ack
                while (queue.isNotEmpty()) {
                    val head = queue.peekFirst() ?: break
                    if (seqAtOrAfter(ack, head.end)) {
                        queue.removeFirst()
                        continue
                    }
                    // Partly acknowledged: keep only the tail.
                    val acked = (ack - head.seq) and SEQ_MASK
                    if (acked in 1 until head.data.size.toLong()) {
                        queue.removeFirst()
                        queue.addFirst(OutSegment(ack, head.data.copyOfRange(acked.toInt(), head.data.size), head.fin, head.sentAt))
                    }
                    break
                }
                session.dupAcks = 0
                session.retransmits = 0
                session.rtoMs = Session.INITIAL_RTO_MS
                // Restart the timer for whatever is still outstanding.
                queue.peekFirst()?.sentAt = System.currentTimeMillis()
                if (queue.isEmpty()) unackedSessions.remove(session)
            } else if (ack == prev && !tcp.hasData && !tcp.isFin &&
                tcp.windowSize == session.deviceWindow && queue.isNotEmpty()) {
                session.dupAcks++
                if (session.dupAcks == 3) {
                    resend = queue.peekFirst()?.also { it.sentAt = System.currentTimeMillis() }
                }
            }
            session.deviceWindow = tcp.windowSize
        }
        resend?.let {
            sendSegment(session, it)
            retransmittedSegments.incrementAndGet()
        }
        if (session.readPaused) selector.wakeup()
    }

    /**
     * Bytes we may read from the server now: what fits in the device's window and
     * in the TUN queue. Reading more would either overrun the app's receive buffer
     * or get dropped at the TUN queue.
     */
    private fun sendBudget(session: Session): Int {
        val acked = session.deviceAckedSeq
        if (acked < 0) return 0
        val inFlight = ((session.sendSeq - acked) and SEQ_MASK).toInt()
        val window = session.deviceWindow - inFlight
        val tunRoom = (tunWriter.remainingCapacity - TUN_RESERVE).coerceAtLeast(0) * mssFor(session.key)
        return minOf(window, tunRoom)
    }

    private fun pauseRead(selKey: SelectionKey, session: Session) {
        session.readPaused = true
        pausedSessions.putIfAbsent(session, System.currentTimeMillis())
        runCatching { selKey.interestOps(selKey.interestOps() and SelectionKey.OP_READ.inv()) }
    }

    /**
     * Server EOF. Its FIN is passed to the device. The session stays until the
     * device closes too, so data the app still sends is not lost.
     */
    private fun onRemoteClosed(selKey: SelectionKey, session: Session) {
        if (!session.remoteClosed) {
            session.remoteClosed = true
            sendFinToDevice(session)
        }
        pausedSessions.remove(session)
        if (session.deviceFinReceived) {
            discard(selKey)
            sessionTable.remove(session.key)
        } else {
            session.tcpState.set(Session.TcpState.CLOSE_WAIT)
            // A channel at EOF stays readable forever; stop reading, keep flushing.
            runCatching { selKey.interestOps(selKey.interestOps() and SelectionKey.OP_READ.inv()) }
        }
    }

    private fun readFromRemote(selKey: SelectionKey, session: Session, buffer: ByteBuffer) {
        val budget = sendBudget(session)
        if (budget <= 0) {
            pauseRead(selKey, session)
            return
        }
        val channel = selKey.channel() as SocketChannel
        buffer.clear()
        buffer.limit(minOf(budget, BUFFER_SIZE))
        val n = channel.read(buffer)
        if (n < 0) {
            onRemoteClosed(selKey, session)
            return
        }
        if (n == 0) return
        buffer.flip()
        val data = ByteArray(n).also { buffer.get(it) }
        session.recordInbound(n)
        forwardedBytesIn.addAndGet(n.toLong())

        if (interception.isEnabled && session.encryptionStatus == EncryptionStatus.CLEARTEXT) {
            runCatching { interception.capture("INBOUND", data, session) }
                .onFailure { Log.w(TAG, "Inbound capture failed: ${it.message}") }
        }

        injectDataToDevice(session, data)
    }

    /**
     * Splits [data] into MSS-sized segments (an IP packet over the 1500-byte TUN
     * MTU is dropped by the device), records each for retransmission, and sends it.
     */
    private fun injectDataToDevice(session: Session, data: ByteArray) {
        val mss = mssFor(session.key)
        val now = System.currentTimeMillis()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + mss, data.size)
            val segment = OutSegment(session.sendSeq and SEQ_MASK, data.copyOfRange(offset, end), fin = false, sentAt = now)
            synchronized(session.unackedToDevice) { session.unackedToDevice.addLast(segment) }
            session.sendSeq = (session.sendSeq + segment.data.size) and SEQ_MASK
            sendSegment(session, segment)
            offset = end
        }
        unackedSessions.add(session)
    }

    private fun sendFinToDevice(session: Session) {
        val segment = OutSegment(session.sendSeq and SEQ_MASK, ByteArray(0), fin = true, sentAt = System.currentTimeMillis())
        synchronized(session.unackedToDevice) { session.unackedToDevice.addLast(segment) }
        session.sendSeq = (session.sendSeq + 1) and SEQ_MASK
        sendSegment(session, segment)
        unackedSessions.add(session)
    }

    /** (Re)sends one recorded segment with the current ACK number and window. */
    private fun sendSegment(session: Session, segment: OutSegment) {
        val packet = buildTcp(
            session.key,
            seqNum = segment.seq,
            ackNum = session.lastAckToDevice,
            ack = true,
            psh = segment.data.isNotEmpty(),
            fin = segment.fin,
            data = segment.data,
            window = advertisedWindow(session),
        )
        // Waits for queue room rather than dropping; if it still fails, the
        // retransmission timer sends it again.
        tunWriter.enqueueBlocking(packet, INJECT_TIMEOUT_MS)
    }

    /** Pure ACK to the device at the current sequence numbers. */
    private fun sendControlToDevice(session: Session) {
        tunWriter.enqueue(
            buildTcp(
                session.key,
                seqNum = session.sendSeq,
                ackNum = session.lastAckToDevice,
                ack = true,
                window = advertisedWindow(session),
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Selector loop
    // ─────────────────────────────────────────────────────────────────────────

    override fun run() {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        Log.d(TAG, "Selector loop started")
        while (running.get()) {
            try {
                drainRegistrations()
                if (selector.select(SELECT_TIMEOUT_MS) > 0) {
                    val keys = selector.selectedKeys().iterator()
                    while (keys.hasNext()) {
                        val selKey = keys.next()
                        keys.remove()
                        handleKey(selKey, buffer)
                    }
                }
                runTimers(System.currentTimeMillis())
            } catch (e: Exception) {
                if (!running.get()) break
                Log.e(TAG, "Selector loop error: ${e.message}")
            }
        }
        Log.d(TAG, "Selector loop exited")
    }

    private fun handleKey(selKey: SelectionKey, buffer: ByteBuffer) {
        val session = selKey.attachment() as? Session
        // A key whose session is gone must be closed here: left registered, its
        // channel stays readable (at EOF) and select() returns at once forever.
        if (session == null || session.isClosed) {
            discard(selKey)
            return
        }
        try {
            if (selKey.isConnectable) {
                finishConnect(selKey, session)
            } else {
                if (selKey.isValid && selKey.isWritable) flushPending(selKey, session)
                if (selKey.isValid && selKey.isReadable) readFromRemote(selKey, session, buffer)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Selector error for ${session.key}: ${e.message}")
            discard(selKey)
            resetSession(session)
            errorCount.incrementAndGet()
        }
    }

    /**
     * Resumes paused reads that now have room, probes devices that advertised a
     * zero window, and resends segments whose retransmission timer expired.
     */
    private fun runTimers(now: Long) {
        for ((session, pausedAt) in pausedSessions) {
            if (session.isClosed || session.remoteClosed) {
                pausedSessions.remove(session)
                continue
            }
            if (sendBudget(session) > 0) {
                pausedSessions.remove(session)
                session.readPaused = false
                session.selectionKey?.takeIf { it.isValid }?.let { key ->
                    runCatching { key.interestOps(key.interestOps() or SelectionKey.OP_READ) }
                }
            } else if (session.deviceWindow == 0 && now - pausedAt >= PROBE_INTERVAL_MS) {
                // Zero-window probe: an ACK one below the next sequence number makes
                // the device answer with its current window, in case its window
                // update was lost.
                pausedSessions[session] = now
                tunWriter.enqueue(buildTcp(session.key,
                    seqNum = (session.sendSeq - 1) and SEQ_MASK, ackNum = session.lastAckToDevice,
                    ack = true, window = advertisedWindow(session)))
            }
        }

        if (now - lastTimerRun < TIMER_INTERVAL_MS) return
        lastTimerRun = now
        for (session in unackedSessions) {
            if (session.isClosed) {
                unackedSessions.remove(session)
                continue
            }
            var resend: OutSegment? = null
            var giveUp = false
            synchronized(session.unackedToDevice) {
                val head = session.unackedToDevice.peekFirst()
                if (head == null) {
                    unackedSessions.remove(session)
                } else if (now - head.sentAt >= session.rtoMs) {
                    if (session.retransmits >= MAX_RETRANSMITS) {
                        giveUp = true
                    } else {
                        session.retransmits++
                        session.rtoMs = minOf(session.rtoMs * 2, MAX_RTO_MS)
                        head.sentAt = now
                        resend = head
                    }
                }
            }
            if (giveUp) {
                Log.w(TAG, "Device stopped acknowledging ${session.key}; resetting")
                resetSession(session)
            } else {
                resend?.let {
                    sendSegment(session, it)
                    retransmittedSegments.incrementAndGet()
                }
            }
        }
    }

    /**
     * Channel registrations requested by other threads, performed by the selector
     * thread between select() calls. Calling register() from another thread blocked
     * while select() held the selector's lock.
     */
    private val pendingRegistrations = ConcurrentLinkedQueue<Runnable>()

    private fun registerOnSelector(
        channel: SocketChannel,
        ops: Int,
        session: Session,
        onRegistered: (() -> Unit)? = null,
    ) {
        pendingRegistrations.add(Runnable {
            if (session.isClosed || !channel.isOpen) {
                runCatching { channel.close() }
                return@Runnable
            }
            try {
                session.selectionKey = channel.register(selector, ops, session)
                // Bytes queued while this was pending need OP_WRITE (connected channels only).
                if (channel.isConnected) {
                    synchronized(session.pendingOut) {
                        if (session.pendingOut.isNotEmpty()) setWriteInterest(session)
                    }
                }
                onRegistered?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Selector registration failed: ${e.message}")
                runCatching { channel.close() }
                resetSession(session)
                errorCount.incrementAndGet()
            }
        })
        selector.wakeup()
    }

    private fun drainRegistrations() {
        while (true) {
            val task = pendingRegistrations.poll() ?: return
            task.run()
        }
    }

    /** Cancels [selKey] and closes its channel, whichever session (if any) it belonged to. */
    private fun discard(selKey: SelectionKey) {
        selKey.cancel()
        runCatching { selKey.channel().close() }
    }

    private fun finishConnect(selKey: SelectionKey, session: Session) {
        val channel = selKey.channel() as SocketChannel
        try {
            if (channel.finishConnect()) {
                connectingCount.decrementAndGet()
                session.tcpState.set(Session.TcpState.ESTABLISHED)
                // Data queued while connecting (the ClientHello of a MITM redirect)
                // is flushed on OP_WRITE, in order.
                synchronized(session.pendingOut) {
                    selKey.interestOps(
                        SelectionKey.OP_READ or (if (session.pendingOut.isEmpty()) 0 else SelectionKey.OP_WRITE)
                    )
                }
                // A MITM redirect reuses the handshake the device already completed.
                if (!session.mitmRedirect) {
                    sendSynAckToDevice(session)
                    Log.d(TAG, "Connected → SYN-ACK sent for ${session.key}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "finishConnect failed for ${session.key}: ${e.message}")
            discard(selKey)
            if (session.mitmRedirect) resetSession(session) else {
                // Connection refused/unreachable before the device got a SYN-ACK.
                sessionTable.remove(session.key)
                sendRst(session.key, synSeq = session.lastDeviceSeq)
            }
            errorCount.incrementAndGet()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Control segments
    // ─────────────────────────────────────────────────────────────────────────

    /** Repeats the SYN-ACK with the same sequence numbers (it crossed a retransmitted SYN). */
    private fun resendSynAckToDevice(session: Session) {
        tunWriter.enqueue(
            buildTcp(
                session.key,
                seqNum = session.synAckSeq,
                ackNum = (session.lastDeviceSeq + 1) and SEQ_MASK,
                syn = true,
                ack = true,
                window = advertisedWindow(session),
            )
        )
    }

    private fun sendSynAckToDevice(session: Session) {
        session.synAckSeq = session.sendSeq and SEQ_MASK
        session.lastAckToDevice = (session.lastDeviceSeq + 1) and SEQ_MASK
        val pkt = buildTcp(
            session.key,
            seqNum = session.synAckSeq,
            ackNum = session.lastAckToDevice,
            syn = true,
            ack = true,
            window = advertisedWindow(session),
        )
        session.sendSeq = (session.synAckSeq + 1) and SEQ_MASK
        // Nothing of ours is in flight yet: the device's first ACK will equal this.
        session.deviceAckedSeq = session.sendSeq
        tunWriter.enqueue(pkt)
    }

    /** ACKs [consumed] bytes past lastAckToDevice (callers have already trimmed retransmits). */
    private fun sendAckToDevice(session: Session, consumed: Int) {
        // Sequence numbers wrap at 2^32; a plain `<=` stopped ACKing for good once
        // the device's sequence space wrapped, and the upload stalled.
        val expected = (session.lastAckToDevice + consumed) and SEQ_MASK
        val ahead = (expected - session.lastAckToDevice) and SEQ_MASK
        if (ahead == 0L || ahead >= HALF_SEQ) return
        session.lastAckToDevice = expected
        sendControlToDevice(session)
    }

    /** RST+ACK for an established [session], then forgets it. */
    private fun resetSession(session: Session) {
        tunWriter.enqueue(
            buildTcp(
                session.key,
                seqNum = session.sendSeq,
                ackNum = session.lastAckToDevice,
                ack = true,
                rst = true,
                window = 0,
            )
        )
        pausedSessions.remove(session)
        unackedSessions.remove(session)
        sessionTable.remove(session.key)
    }

    /**
     * RST in answer to [tcp] when there is no usable session (RFC 793 §3.4): a
     * segment with ACK gets seq = its ACK number; a SYN gets RST+ACK acknowledging
     * it, which is the only form the app's stack accepts in SYN-SENT.
     */
    private fun sendRst(key: SessionKey, tcp: TcpPacket) {
        if (tcp.flagAck) {
            tunWriter.enqueue(buildTcp(key, seqNum = tcp.acknowledgmentNumber, ackNum = 0L, rst = true, window = 0))
        } else {
            val ackNum = (tcp.sequenceNumber + tcp.data.size + (if (tcp.flagSyn) 1 else 0) +
                (if (tcp.flagFin) 1 else 0)) and SEQ_MASK
            tunWriter.enqueue(buildTcp(key, seqNum = 0L, ackNum = ackNum, ack = true, rst = true, window = 0))
        }
    }

    /** RST+ACK refusing the SYN with sequence number [synSeq]. */
    private fun sendRst(key: SessionKey, synSeq: Long) {
        tunWriter.enqueue(buildTcp(key, seqNum = 0L, ackNum = (synSeq + 1) and SEQ_MASK, ack = true, rst = true, window = 0))
    }

    private fun mssFor(key: SessionKey): Int = if (key.sourceIp.contains(':')) MSS_V6 else MSS_V4

    /** A server→device segment for the connection [key] (addresses reversed). */
    @Suppress("LongParameterList")
    private fun buildTcp(
        key: SessionKey,
        seqNum: Long,
        ackNum: Long,
        syn: Boolean = false,
        ack: Boolean = false,
        psh: Boolean = false,
        fin: Boolean = false,
        rst: Boolean = false,
        data: ByteArray = ByteArray(0),
        window: Int = 0xFFFF,
    ): ByteArray {
        if (key.sourceIp.contains(':')) {
            return Ipv6Packet.buildTcp(
                srcAddr = key.destinationIp, srcPort = key.destinationPort,
                dstAddr = key.sourceIp, dstPort = key.sourcePort,
                seqNum = seqNum and SEQ_MASK, ackNum = ackNum and SEQ_MASK,
                syn = syn, ack = ack, psh = psh, fin = fin, rst = rst,
                data = data, window = window,
            )
        }
        val totalLen = 40 + data.size
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x45.toByte()
        pkt[2] = (totalLen ushr 8).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        pkt[6] = 0x40.toByte()   // DF
        pkt[8] = 64
        pkt[9] = 6
        key.destinationIp.split('.').forEachIndexed { i, s -> pkt[12 + i] = s.toInt().toByte() }
        key.sourceIp.split('.').forEachIndexed { i, s -> pkt[16 + i] = s.toInt().toByte() }

        pkt[20] = (key.destinationPort ushr 8).toByte()
        pkt[21] = (key.destinationPort and 0xFF).toByte()
        pkt[22] = (key.sourcePort ushr 8).toByte()
        pkt[23] = (key.sourcePort and 0xFF).toByte()

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
        // Receive window: shrinks as the upstream send queue fills (flow control).
        pkt[34] = (window ushr 8).toByte()
        pkt[35] = (window and 0xFF).toByte()

        if (data.isNotEmpty()) data.copyInto(pkt, 40)
        Checksum.setIpv4HeaderChecksum(pkt, 0)
        Checksum.setTcpChecksum(pkt, 0)
        return pkt
    }
}
