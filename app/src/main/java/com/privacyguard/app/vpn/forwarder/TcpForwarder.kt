package com.privacyguard.vpn.forwarder

import android.util.Log
import com.privacyguard.app.BuildConfig
import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.core.session.SessionTable
import com.privacyguard.vpn.forwarder.TcpSegments.ACK
import com.privacyguard.vpn.forwarder.TcpSegments.FIN
import com.privacyguard.vpn.forwarder.TcpSegments.PSH
import com.privacyguard.vpn.forwarder.TcpSegments.RST
import com.privacyguard.vpn.forwarder.TcpSegments.SYN
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * User-space TCP proxy: terminates each device connection arriving on the TUN
 * and relays its bytes over a real (protected) socket.
 *
 * Threads: [handle] runs on the TUN reader thread; all selector work (register,
 * interest changes, connect/read/write readiness) runs on the "tcp-selector"
 * thread. Other threads hand selector work over through [runOnSelector], never
 * by calling `register()` themselves: `register()` blocks while the selector is
 * inside `select()`, and the selector thread re-enters `select()` so quickly
 * that the reader thread used to starve for seconds on every new connection,
 * freezing all traffic on the phone.
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
) : Runnable {

    companion object {
        private const val TAG = "TcpForwarder"
        private const val BUFFER_SIZE = 65_536
        private const val MSS = TcpSegments.MSS
        /** Device → server bytes we buffer (and ACK) while the server socket is full. */
        private const val MAX_PENDING_OUT = 512 * 1024
    }

    private val selector = Selector.open()
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val selectorTasks = ConcurrentLinkedQueue<Runnable>()

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

    /** Runs [task] on the selector thread (immediately if already on it). */
    private fun runOnSelector(task: Runnable) {
        if (Thread.currentThread() === thread) { task.run(); return }
        selectorTasks.add(task)
        selector.wakeup()
    }

    fun handle(ip: IpPacket, tcp: TcpPacket, ownerUid: Int = -1, ownerPackage: String? = null) {
        val key = SessionKey.of(
            ip.sourceIp, tcp.sourcePort,
            ip.destinationIp, tcp.destinationPort,
            IpPacket.PROTO_TCP,
        )
        if (tcp.isSyn) {
            handleSyn(ip, tcp, key, ownerUid, ownerPackage)
            return
        }
        val session = sessionTable.get(key)
        if (session == null) {
            // A connection we no longer know (server closed it, it timed out, or the
            // VPN restarted). Resetting it makes the app reconnect at once instead of
            // waiting for its own timeout on a dead socket.
            if (!tcp.isRst && (tcp.hasData || tcp.isFin)) sendRstFor(ip, tcp)
            return
        }
        if (tcp.isRst) {
            sessionTable.remove(key)
            return
        }
        if (session.ownerUid == -1 && ownerUid >= 0) {
            session.ownerUid = ownerUid
            session.ownerPackage = ownerPackage
        }
        if (tcp.flagAck) onDeviceAck(session, tcp)
        if (tcp.hasData) handleData(ip, tcp, key, session)
        if (tcp.isFin) handleFin(tcp, session)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleSyn(ip: IpPacket, tcp: TcpPacket, key: SessionKey, ownerUid: Int, ownerPackage: String?) {
        sessionTable.get(key)?.let { existing ->
            if (existing.lastDeviceSeq == tcp.sequenceNumber) {
                // Retransmitted SYN: the upstream connect is still running, or our
                // SYN-ACK got lost. Opening a second socket here leaked the first.
                if (existing.synAckSent) sendSynAckToDevice(existing)
                return
            }
            sessionTable.remove(key)   // the port was reused for a new connection
        }

        val session = sessionTable.getOrCreate(key, uid = ownerUid, ownerPackage = ownerPackage)
        session.lastDeviceSeq = tcp.sequenceNumber
        session.deviceWindowShift = TcpSegments.windowShift(tcp.options)
        // The window in a SYN is never scaled.
        session.deviceWindow = tcp.windowSize.toLong()

        val cfg = dualVpnConfig()
        if (cfg.isValid) {
            handleSynDualVpn(ip, tcp, key, session, cfg)
            return
        }

        try {
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            channel.socket().tcpNoDelay = true
            protectSocket(channel.socket())
            channel.connect(InetSocketAddress(ip.destinationIp, tcp.destinationPort))

            session.tcpChannel = channel
            session.tcpState.set(Session.TcpState.SYN_RECEIVED)
            connectingCount.incrementAndGet()
            runOnSelector {
                if (session.isClosed) return@runOnSelector
                try {
                    session.selectionKey = channel.register(selector, SelectionKey.OP_CONNECT, session)
                } catch (e: Exception) {
                    connectingCount.decrementAndGet()
                    failSession(session, "register: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SYN error $key: ${e.message}")
            sessionTable.remove(key)
            sendRstFor(ip, tcp)
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

                runOnSelector {
                    if (session.isClosed) return@runOnSelector
                    try {
                        session.selectionKey = channel.register(selector, SelectionKey.OP_READ, session)
                        sendSynAckToDevice(session)
                    } catch (e: Exception) {
                        failSession(session, "register: ${e.message}")
                    }
                }
                Log.i(TAG, "Dual VPN tunnel ready: ${cfg.firstHopHost} → ${cfg.secondHopHost} → ${ip.destinationIp}:${tcp.destinationPort}")
            } catch (e: Exception) {
                Log.w(TAG, "Dual VPN SYN failed for $key: ${e.message}")
                sessionTable.remove(key)
                sendRstFor(ip, tcp)
                errorCount.incrementAndGet()
            } finally {
                connectingCount.decrementAndGet()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Device → server
    // ─────────────────────────────────────────────────────────────────────────

    /** Records the device's ACK and window, and resumes reading if the window reopened. */
    private fun onDeviceAck(session: Session, tcp: TcpPacket) {
        val ack = tcp.acknowledgmentNumber
        // Ignore ACKs for data we never sent and stale ones that arrive reordered.
        if (TcpSegments.diff(ack, session.sendSeq) > 0) return
        if (TcpSegments.diff(ack, session.deviceAcked) < 0) return
        session.deviceAcked = ack
        val shift = session.deviceWindowShift.coerceAtLeast(0)
        session.deviceWindow = tcp.windowSize.toLong() shl shift
        if (session.readPaused && availableDeviceWindow(session) > 0) {
            session.readPaused = false
            runOnSelector { setInterest(session, SelectionKey.OP_READ, true) }
        }
    }

    private fun handleData(ip: IpPacket, tcp: TcpPacket, key: SessionKey, session: Session) {
        val state = session.tcpState.get()
        if (state != Session.TcpState.ESTABLISHED && state != Session.TcpState.SYN_RECEIVED) return
        if (session.deviceFinReceived) return

        // Keep only bytes we have not seen yet. A retransmit (our ACK was late or
        // lost) must not reach the server twice: duplicated bytes corrupt the TLS
        // stream and the server drops the connection.
        val expected = session.lastAckToDevice
        val offset = TcpSegments.diff(expected, tcp.sequenceNumber)
        if (offset < 0) {
            // A gap: an earlier segment is missing. Drop this one and re-ACK what we
            // have, so the device retransmits from there.
            sendAck(session)
            return
        }
        if (offset >= tcp.data.size) {
            sendAck(session)   // entirely old: re-ACK so the device stops retransmitting
            return
        }
        val data = if (offset == 0L) tcp.data else tcp.data.copyOfRange(offset.toInt(), tcp.data.size)

        if (!session.encryptionClassified) {
            // ── Buffer a multi-segment ClientHello before classifying ──────────
            // Chrome's post-quantum ClientHello (~1.6 KB) spans several TCP
            // segments, and the SNI hostname lives past the first segment. Judging
            // on the first segment alone misses the hostname, so MITM never fires
            // and the connection slips through unintercepted. Accumulate the first
            // TLS record, then classify and route on the whole thing.
            var clientHelloBytes = data
            if (key.destinationPort == 443 && !session.isMitmIntercepted &&
                (session.httpOutBytes.isNotEmpty() || (data[0].toInt() and 0xFF) == 0x16)) {
                val buffered = session.httpOutBytes + data
                val recordLen = if (buffered.size >= 5)
                    5 + (((buffered[3].toInt() and 0xFF) shl 8) or (buffered[4].toInt() and 0xFF))
                    else Int.MAX_VALUE
                if (buffered.size < recordLen && buffered.size < 16_384) {
                    session.httpOutBytes = buffered            // keep buffering, ACK, wait
                    acceptFromDevice(session, data.size)
                    return
                }
                clientHelloBytes = buffered
                session.httpOutBytes = ByteArray(0)
            }

            val result = encEnforcer.classify(key.destinationPort, clientHelloBytes)
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
                resetSession(session)
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
                    mitmInterceptedCount.incrementAndGet()

                    // Close the existing upstream channel (no TLS data exchanged yet —
                    // only the TCP handshake), then reconnect to the local MITM port.
                    session.selectionKey?.cancel()
                    runCatching { session.tcpChannel?.close() }

                    try {
                        mitmChannel.configureBlocking(false)
                        // Loopback is local — do NOT protect() this socket.
                        mitmChannel.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), mitmPort))
                        session.tcpChannel = mitmChannel
                        connectingCount.incrementAndGet()
                        runOnSelector {
                            if (session.isClosed) return@runOnSelector
                            try {
                                session.selectionKey = mitmChannel.register(selector, SelectionKey.OP_CONNECT, session)
                            } catch (e: Exception) {
                                connectingCount.decrementAndGet()
                                failSession(session, "MITM register: ${e.message}")
                            }
                        }
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
                    acceptFromDevice(session, data.size)
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
                    resetSession(session)
                    return
                }
            }

            // A buffered multi-segment ClientHello that did NOT go to MITM (pinned,
            // MITM off, or intercept failed) is forwarded whole to the real server.
            if (clientHelloBytes !== data) {
                // Earlier segments were already ACKed; send their bytes too.
                if (!writeToServer(session, clientHelloBytes, data.size)) return
                capture(session, data)
                return
            }
        }

        capture(session, data)
        writeToServer(session, data, data.size)
    }

    /**
     * Sends [bytes] to the server (buffering what the socket cannot take now) and
     * ACKs [newBytes] new device bytes. Returns false if the segment was refused.
     */
    private fun writeToServer(session: Session, bytes: ByteArray, newBytes: Int): Boolean {
        val channel = session.tcpChannel ?: return false
        val needWriteInterest: Boolean
        synchronized(session.outLock) {
            if (session.outPendingBytes + bytes.size > MAX_PENDING_OUT) {
                // Server far behind: refuse the segment unACKed; the device resends it.
                return false
            }
            val buf = ByteBuffer.wrap(bytes)
            try {
                // Only write directly when nothing is queued, or bytes would reorder.
                // Before the upstream connect finishes there is nothing to write to.
                if (session.outPending.isEmpty() && channel.isConnected) {
                    while (buf.hasRemaining()) { if (channel.write(buf) <= 0) break }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Forward failed for ${session.key}: ${e.message}")
                errorCount.incrementAndGet()
                resetSession(session)
                return false
            }
            needWriteInterest = buf.hasRemaining()
            if (needWriteInterest) {
                session.outPending.addLast(buf)
                session.outPendingBytes += buf.remaining()
            }
        }
        if (needWriteInterest) runOnSelector { setInterest(session, SelectionKey.OP_WRITE, true) }
        forwardedBytesOut.addAndGet(newBytes.toLong())
        acceptFromDevice(session, newBytes)
        return true
    }

    /** Marks [n] more device bytes as received and ACKs them. */
    private fun acceptFromDevice(session: Session, n: Int) {
        session.recordOutbound(n)
        session.lastAckToDevice = TcpSegments.add(session.lastAckToDevice, n.toLong())
        sendAck(session)
    }

    // ── PAYLOAD CAPTURE (fully async — never touches the forwarding path) ──
    private fun capture(session: Session, data: ByteArray) {
        if (!interception.isEnabled || isPrivacyGuardTraffic(session.ownerPackage)) return
        val enc  = session.encryptionStatus
        // Metadata-only stub for TLS sessions we are NOT decrypting. MITM'd sessions
        // log real decrypted entries via capture — a stub there would show
        // up as a bogus "encrypted" entry next to the plaintext.
        val needStub = session.key.destinationPort == 443 && session.tlsSni != null &&
            !session.metadataLogged && !session.isMitmIntercepted
        if (needStub) session.metadataLogged = true   // set flag on capture thread
        if (!needStub && enc != EncryptionStatus.CLEARTEXT) return
        val snap = data.copyOf()          // snapshot before forwarding

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

    private fun isPrivacyGuardTraffic(ownerPackage: String?): Boolean {
        return ownerPackage == BuildConfig.APPLICATION_ID ||
                ownerPackage?.startsWith("com.privacyguard.app") == true
    }

    private fun handleFin(tcp: TcpPacket, session: Session) {
        val finSeq = TcpSegments.add(tcp.sequenceNumber, tcp.data.size.toLong())
        if (session.deviceFinReceived) {
            sendAck(session)   // retransmitted FIN
            return
        }
        // Data before the FIN is missing: ignore the FIN, the device will resend it.
        if (finSeq != session.lastAckToDevice) return

        session.deviceFinReceived = true
        session.lastAckToDevice = TcpSegments.add(finSeq, 1)
        sendAck(session)
        if (session.finSentToDevice) {
            sessionTable.remove(session.key)   // both sides closed
            return
        }
        // Half-close: stop sending to the server, keep relaying its response until
        // it closes too.
        session.tcpState.set(Session.TcpState.FIN_WAIT)
        val flushNow = synchronized(session.outLock) {
            if (session.outPending.isEmpty()) true else { session.shutdownAfterFlush = true; false }
        }
        if (flushNow) runCatching { session.tcpChannel?.shutdownOutput() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Selector loop
    // ─────────────────────────────────────────────────────────────────────────

    override fun run() {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        Log.d(TAG, "Selector loop started")
        while (running.get()) {
            try {
                while (true) {
                    val task = selectorTasks.poll() ?: break
                    try { task.run() } catch (e: Exception) { Log.w(TAG, "Selector task failed: ${e.message}") }
                }
                if (selector.select(1_000L) == 0) continue
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val selKey = keys.next()
                    keys.remove()
                    val session = selKey.attachment() as? Session
                    // A key whose session is gone must be closed here: left registered,
                    // its channel stays readable (at EOF) and select() returns at once
                    // forever, spinning this thread at 100% CPU.
                    if (session == null || session.isClosed || !selKey.isValid) {
                        discard(selKey)
                        continue
                    }
                    try {
                        if (selKey.isConnectable) {
                            finishConnect(selKey, session)
                            continue
                        }
                        if (selKey.isWritable) flushPending(selKey, session)
                        if (selKey.isValid && selKey.isReadable) readFromRemote(selKey, session, buffer)
                    } catch (e: Exception) {
                        Log.w(TAG, "Selector error for ${session.key}: ${e.message}")
                        errorCount.incrementAndGet()
                        resetSession(session)
                    }
                }
            } catch (e: Exception) {
                if (!running.get()) break
                Log.e(TAG, "Selector loop error: ${e.message}")
            }
        }
        Log.d(TAG, "Selector loop exited")
    }

    /** Cancels [selKey] and closes its channel, whichever session (if any) it belonged to. */
    private fun discard(selKey: SelectionKey) {
        selKey.cancel()
        runCatching { selKey.channel().close() }
    }

    /** Selector thread only. */
    private fun setInterest(session: Session, op: Int, on: Boolean) {
        val key = session.selectionKey ?: return
        if (!key.isValid) return
        val ops = key.interestOps()
        key.interestOps(if (on) ops or op else ops and op.inv())
    }

    private fun finishConnect(selKey: SelectionKey, session: Session) {
        val channel = selKey.channel() as SocketChannel
        try {
            if (!channel.finishConnect()) return
        } catch (e: Exception) {
            connectingCount.decrementAndGet()
            // Refused / unreachable: tell the app now instead of letting its SYN time out.
            failSession(session, "connect: ${e.message}")
            return
        }
        connectingCount.decrementAndGet()
        session.tcpState.set(Session.TcpState.ESTABLISHED)
        // The first connect is the real server; a MITM redirect reconnects to loopback.
        if (session.connectMs == 0L) session.connectMs = System.currentTimeMillis() - session.createdAt
        var ops = SelectionKey.OP_READ
        synchronized(session.outLock) { if (session.outPending.isNotEmpty()) ops = ops or SelectionKey.OP_WRITE }
        selKey.interestOps(ops)

        val pending = session.pendingMitmData
        if (pending != null) {
            // This is a MITM redirect connect — replay the ClientHello into the
            // local MITM server socket. Do NOT send another SYN-ACK; the device
            // already received one when the original channel connected.
            session.pendingMitmData = null
            try {
                val buf = ByteBuffer.wrap(pending)
                while (buf.hasRemaining()) {
                    if (channel.write(buf) <= 0) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to replay MITM ClientHello: ${e.message}")
            }
        } else {
            sendSynAckToDevice(session)
        }
    }

    private fun flushPending(selKey: SelectionKey, session: Session) {
        val channel = selKey.channel() as SocketChannel
        var drained: Boolean
        synchronized(session.outLock) {
            while (session.outPending.isNotEmpty()) {
                val buf = session.outPending.first()
                val n = channel.write(buf)
                session.outPendingBytes -= n
                if (buf.hasRemaining()) break
                session.outPending.removeFirst()
            }
            drained = session.outPending.isEmpty()
            if (drained) {
                selKey.interestOps(selKey.interestOps() and SelectionKey.OP_WRITE.inv())
                if (session.shutdownAfterFlush) {
                    session.shutdownAfterFlush = false
                    runCatching { channel.shutdownOutput() }
                }
            }
        }
        if (drained && session.windowLimited) sendAck(session)   // window update
    }

    private fun readFromRemote(selKey: SelectionKey, session: Session, buffer: ByteBuffer) {
        val window = availableDeviceWindow(session)
        if (window <= 0) {
            pauseRead(session)
            return
        }
        val channel = selKey.channel() as SocketChannel
        buffer.clear()
        buffer.limit(minOf(BUFFER_SIZE.toLong(), window).toInt())
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
    }

    /** Bytes the device can still accept: its window minus what is in flight. */
    private fun availableDeviceWindow(session: Session): Long {
        val inFlight = TcpSegments.diff(session.sendSeq, session.deviceAcked).coerceAtLeast(0)
        return session.deviceWindow - inFlight
    }

    /** Selector thread only. */
    private fun pauseRead(session: Session) {
        setInterest(session, SelectionKey.OP_READ, false)
        session.readPaused = true
        // An ACK may have opened the window between the check and the flag; the
        // reader thread then saw readPaused == false and did not resume us.
        if (availableDeviceWindow(session) > 0) {
            session.readPaused = false
            setInterest(session, SelectionKey.OP_READ, true)
        }
    }

    /** The server closed its side: pass the FIN on so the app sees the close at once. */
    private fun onRemoteClosed(selKey: SelectionKey, session: Session) {
        selKey.interestOps(selKey.interestOps() and SelectionKey.OP_READ.inv())
        if (!session.finSentToDevice) {
            session.finSentToDevice = true
            sendToDevice(session, FIN or ACK)
            session.sendSeq = TcpSegments.add(session.sendSeq, 1)
        }
        if (session.deviceFinReceived) sessionTable.remove(session.key)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server → device
    // ─────────────────────────────────────────────────────────────────────────

    private fun sendSynAckToDevice(session: Session) {
        if (!session.synAckSent) {
            session.lastAckToDevice = TcpSegments.add(session.lastDeviceSeq, 1)
            session.sendSeq = TcpSegments.add(session.localIsn, 1)
            session.deviceAcked = session.sendSeq
            session.synAckSent = true
        }
        tunWriter.enqueue(TcpSegments.build(
            srcIp = session.key.destinationIp,
            srcPort = session.key.destinationPort,
            dstIp = session.key.sourceIp,
            dstPort = session.key.sourcePort,
            seq = session.localIsn,
            ack = session.lastAckToDevice,
            flags = SYN or ACK,
            windowScale = session.deviceWindowShift >= 0,
        ))
    }

    private fun sendAck(session: Session) = sendToDevice(session, ACK)

    /** Sends a segment from the server's side at the current sequence numbers. */
    private fun sendToDevice(session: Session, flags: Int) {
        tunWriter.enqueue(TcpSegments.build(
            srcIp = session.key.destinationIp,
            srcPort = session.key.destinationPort,
            dstIp = session.key.sourceIp,
            dstPort = session.key.sourcePort,
            seq = session.sendSeq,
            ack = session.lastAckToDevice,
            flags = flags,
            window = receiveWindow(session),
        ))
    }

    /** Our receive window: buffer space left for bytes the server has not taken yet. */
    private fun receiveWindow(session: Session): Int {
        val free = (MAX_PENDING_OUT - session.outPendingBytes).coerceIn(0, 65_535)
        session.windowLimited = free < 65_535
        return free
    }

    private fun injectDataToDevice(session: Session, data: ByteArray) {
        // The TUN MTU is 1500, so a single injected IP packet must stay within it
        // (1500 - 20 IP - 20 TCP = 1460 bytes of payload). A larger server flight —
        // e.g. a TLS ServerHello carrying the certificate — must be split into
        // MSS-sized TCP segments, or the device silently drops the oversized packet
        // and keeps retransmitting, so the TLS handshake never completes.
        var offset = 0
        val window = receiveWindow(session)
        while (offset < data.size) {
            val end = minOf(offset + MSS, data.size)
            tunWriter.enqueue(TcpSegments.build(
                srcIp = session.key.destinationIp,
                srcPort = session.key.destinationPort,
                dstIp = session.key.sourceIp,
                dstPort = session.key.sourcePort,
                seq = session.sendSeq,
                ack = session.lastAckToDevice,
                flags = if (end == data.size) ACK or PSH else ACK,   // PSH only on the final segment
                window = window,
                data = data,
                dataOffset = offset,
                dataLength = end - offset,
            ))
            session.sendSeq = TcpSegments.add(session.sendSeq, (end - offset).toLong())
            offset = end
        }
    }

    /** Resets an established session towards the device and drops it. */
    private fun resetSession(session: Session) {
        sendToDevice(session, RST or ACK)
        sessionTable.remove(session.key)
    }

    /** Connect/register failure: reset the device's connection attempt, drop the session. */
    private fun failSession(session: Session, reason: String) {
        Log.w(TAG, "Session ${session.key} failed — $reason")
        errorCount.incrementAndGet()
        // Already established with the device (e.g. a MITM redirect): an RST must
        // carry in-window sequence numbers or the device ignores it.
        if (session.synAckSent) {
            resetSession(session)
            return
        }
        tunWriter.enqueue(TcpSegments.build(
            srcIp = session.key.destinationIp,
            srcPort = session.key.destinationPort,
            dstIp = session.key.sourceIp,
            dstPort = session.key.sourcePort,
            seq = 0,
            ack = TcpSegments.add(session.lastDeviceSeq, 1),
            flags = RST or ACK,
        ))
        sessionTable.remove(session.key)
    }

    /**
     * RST in reply to [tcp] for a connection we have no state for (RFC 793 §3.4):
     * if it carried an ACK, the RST takes that as its sequence number; otherwise
     * it acknowledges the segment, which a SYN-SENT socket requires to accept it.
     */
    private fun sendRstFor(ip: IpPacket, tcp: TcpPacket) {
        val (seq, ack, flags) = if (tcp.flagAck) {
            Triple(tcp.acknowledgmentNumber, 0L, RST)
        } else {
            val len = tcp.data.size + (if (tcp.flagSyn) 1 else 0) + (if (tcp.flagFin) 1 else 0)
            Triple(0L, TcpSegments.add(tcp.sequenceNumber, len.toLong()), RST or ACK)
        }
        tunWriter.enqueue(TcpSegments.build(
            srcIp = ip.destinationIp,
            srcPort = tcp.destinationPort,
            dstIp = ip.sourceIp,
            dstPort = tcp.sourcePort,
            seq = seq,
            ack = ack,
            flags = flags,
        ))
    }

    /** Refuses a new connection the filter blocked: RST makes the app fail fast. */
    fun refuse(ip: IpPacket, tcp: TcpPacket) = sendRstFor(ip, tcp)
}
