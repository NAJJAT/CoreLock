package com.privacyguard.vpn.forwarder

import android.util.Log
import com.privacyguard.core.packet.Ipv6Packet
import com.privacyguard.core.packet.TcpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.session.SessionKey
import com.privacyguard.vpn.tunnel.TunWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lightweight IPv6 TCP/UDP proxy.
 *
 * Unlike the full TcpForwarder (which maintains a complete TCP state machine
 * for IPv4), this proxy uses a relay model:
 *
 *   Device ──(IPv6 TUN)──▶ Ipv6Proxy ──(real IPv6 socket)──▶ Internet
 *
 * TCP sessions are proxied via SocketChannel. The proxy manages SYN/SYN-ACK
 * handshake and data relay, writing back proper IPv6 TCP packets to TUN.
 *
 * UDP uses a stateless per-source-port relay via DatagramSocket.
 *
 * Every relayed flow is reported once through [onFlowClosed] when it ends, so
 * IPv6 traffic shows up in history like IPv4 does (it used to be invisible).
 */
class Ipv6Proxy(
    private val tunWriter: TunWriter,
    private val protectSocket: (java.net.Socket) -> Boolean,
    private val protectUdpSocket: (java.net.DatagramSocket) -> Boolean,
    private val scope: CoroutineScope,
    private val onFlowClosed: ((Ipv6Flow) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "Ipv6Proxy"
        private const val RELAY_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val BUF_SIZE = 8192
    }

    /** Active TCP relay sessions keyed by SessionKey. */
    private val tcpSessions = ConcurrentHashMap<SessionKey, TcpRelaySession>()

    /** UDP relay sockets keyed by (srcIp, srcPort, dstIp, dstPort). */
    private val udpRelays = ConcurrentHashMap<String, UdpRelay>()

    // ── Entry point ───────────────────────────────────────────────────────────

    fun handle(ipv6: Ipv6Packet) {
        val payload = ipv6.rawPacket
        val off     = Ipv6Packet.HEADER_LEN

        when (ipv6.nextHeader) {
            Ipv6Packet.PROTO_TCP -> {
                val tcp = TcpPacket.parse(payload, off, payload.size) ?: return
                handleTcp(ipv6, tcp)
            }
            Ipv6Packet.PROTO_UDP -> {
                if (payload.size < off + 8) return
                val srcPort = ((payload[off].toInt() and 0xFF) shl 8) or (payload[off + 1].toInt() and 0xFF)
                val dstPort = ((payload[off + 2].toInt() and 0xFF) shl 8) or (payload[off + 3].toInt() and 0xFF)
                val udpLen  = ((payload[off + 4].toInt() and 0xFF) shl 8) or (payload[off + 5].toInt() and 0xFF)
                // A length below the 8-byte header made copyOfRange throw.
                if (udpLen < 8) return
                val data    = payload.copyOfRange(off + 8, (off + udpLen).coerceAtMost(payload.size))
                handleUdp(ipv6, srcPort, dstPort, data)
            }
            else -> Log.d(TAG, "Unsupported IPv6 next header: ${ipv6.nextHeader}")
        }
    }

    /** Answers a TCP SYN with RST, so the app gives up on this address immediately. */
    fun refuse(ipv6: Ipv6Packet) {
        val tcp = TcpPacket.parse(ipv6.rawPacket, Ipv6Packet.HEADER_LEN, ipv6.rawPacket.size) ?: return
        sendRst(ipv6, tcp)
    }

    // ── TCP ───────────────────────────────────────────────────────────────────

    private fun handleTcp(ipv6: Ipv6Packet, tcp: TcpPacket) {
        val key = SessionKey.of(ipv6.sourceIp, tcp.sourcePort, ipv6.destinationIp, tcp.destinationPort, 6)

        if (tcp.isSyn && !tcp.flagAck) {
            // A retransmitted SYN while connecting used to open (and leak) another socket.
            if (key in pendingConnects) return
            tcpSessions.remove(key)?.close()   // reused port: drop the stale relay
            if (!pendingConnects.add(key)) return
            openTcpSession(key, ipv6, tcp)
            return
        }

        val session = tcpSessions[key] ?: return
        when {
            tcp.flagRst -> {
                session.close()
                tcpSessions.remove(key, session)
            }
            else -> {
                if (tcp.data.isNotEmpty()) session.onClientData(tcp.sequenceNumber, tcp.data)
                // FIN is a half-close: keep relaying the server's remaining response.
                if (tcp.flagFin) session.onClientFin(tcp.sequenceNumber, tcp.data.size)
            }
        }
    }

    private val pendingConnects = ConcurrentHashMap.newKeySet<SessionKey>()

    private fun openTcpSession(key: SessionKey, ipv6: Ipv6Packet, tcp: TcpPacket) {
        scope.launch(Dispatchers.IO) {
            var session: TcpRelaySession? = null
            var channel: SocketChannel? = null
            try {
                channel = SocketChannel.open()
                protectSocket(channel.socket())
                // Connect with a timeout: a blocking connect to a black-holed address
                // otherwise holds this IO thread for minutes.
                channel.socket().connect(InetSocketAddress(
                    Inet6Address.getByName(ipv6.destinationIp),
                    tcp.destinationPort,
                ), CONNECT_TIMEOUT_MS)

                val relay = TcpRelaySession(
                    channel        = channel,
                    srcIp          = ipv6.destinationIp,
                    srcPort        = tcp.destinationPort,
                    dstIp          = ipv6.sourceIp,
                    dstPort        = tcp.sourcePort,
                    serverSeqInit  = (Math.random() * Int.MAX_VALUE).toLong(),
                    clientSeqInit  = (tcp.sequenceNumber + 1) and 0xFFFFFFFFL,
                    tunWriter      = tunWriter,
                    onClosed       = onFlowClosed,
                )
                session = relay
                tcpSessions[key] = relay
                pendingConnects.remove(key)

                // Send SYN-ACK back to device
                relay.sendSynAck()

                // Start relay loop
                relay.startRelay()
            } catch (e: Exception) {
                Log.e(TAG, "IPv6 TCP connect failed: ${e.message}")
                if (session == null) runCatching { channel?.close() }
                sendRst(ipv6, tcp)
            } finally {
                pendingConnects.remove(key)
                // The relay ended (remote closed or failed): forget it, or the map grew forever.
                session?.let { tcpSessions.remove(key, it) }
            }
        }
    }

    private fun sendRst(ipv6: Ipv6Packet, tcp: TcpPacket) {
        val pkt = Ipv6Packet.buildTcp(
            srcAddr = ipv6.destinationIp, srcPort = tcp.destinationPort,
            dstAddr = ipv6.sourceIp,      dstPort = tcp.sourcePort,
            seqNum = 0, ackNum = tcp.sequenceNumber + 1,
            rst = true, ack = true,
        )
        tunWriter.enqueue(pkt)
    }

    // ── UDP ───────────────────────────────────────────────────────────────────

    private fun handleUdp(ipv6: Ipv6Packet, srcPort: Int, dstPort: Int, data: ByteArray) {
        val relayKey = "${ipv6.sourceIp}:$srcPort→${ipv6.destinationIp}:$dstPort"

        // Evict relay whose socket was closed by the soTimeout in startReceiveLoop.
        val existing = udpRelays[relayKey]
        if (existing?.socket?.isClosed == true) udpRelays.remove(relayKey, existing)

        val relay = udpRelays.getOrPut(relayKey) {
            val socket = DatagramSocket().apply {
                soTimeout = RELAY_TIMEOUT_MS.toInt()   // self-expire idle relays
            }
            protectUdpSocket(socket)
            val r = UdpRelay(
                socket    = socket,
                srcIp     = ipv6.sourceIp,
                srcPort   = srcPort,
                dstIp     = ipv6.destinationIp,
                dstPort   = dstPort,
                tunWriter = tunWriter,
                relayKey  = relayKey,
                relays    = udpRelays,
                onClosed  = onFlowClosed,
            )
            scope.launch(Dispatchers.IO) { r.startReceiveLoop() }
            r
        }
        relay.send(data, ipv6.destinationIp, dstPort)
    }
}

// ── TCP relay session ─────────────────────────────────────────────────────────

private class TcpRelaySession(
    private val channel:   SocketChannel,
    private val srcIp:     String,
    private val srcPort:   Int,
    private val dstIp:     String,
    private val dstPort:   Int,
    serverSeqInit:         Long,
    clientSeqInit:         Long,
    private val tunWriter: TunWriter,
    private val onClosed:  ((Ipv6Flow) -> Unit)? = null,
) {
    // serverSeq is advanced by the relay thread, clientSeq by the TUN reader thread.
    @Volatile private var serverSeq = serverSeqInit
    @Volatile private var clientSeq = clientSeqInit
    private val startedAt = System.currentTimeMillis()
    private val sent = java.util.concurrent.atomic.AtomicLong()
    private val received = java.util.concurrent.atomic.AtomicLong()
    private val reported = java.util.concurrent.atomic.AtomicBoolean(false)
    // First client bytes, kept until the TLS server name is found (ClientHellos can span segments).
    @Volatile private var helloBuf: ByteArray? = ByteArray(0)
    @Volatile private var sni: String? = null
    @Volatile private var clientFinished = false

    private companion object {
        // TUN MTU 1500 − 40 IPv6 − 20 TCP: larger injected packets are dropped by the device.
        const val MSS = 1440
    }

    fun sendSynAck() {
        val pkt = Ipv6Packet.buildTcp(
            srcAddr = srcIp, srcPort = srcPort,
            dstAddr = dstIp, dstPort = dstPort,
            seqNum = serverSeq, ackNum = clientSeq,
            syn = true, ack = true,
        )
        serverSeq = (serverSeq + 1) and 0xFFFFFFFFL
        tunWriter.enqueue(pkt)
    }

    private fun sendAck() {
        tunWriter.enqueue(Ipv6Packet.buildTcp(
            srcAddr = srcIp, srcPort = srcPort,
            dstAddr = dstIp, dstPort = dstPort,
            seqNum = serverSeq, ackNum = clientSeq,
            ack = true,
        ))
    }

    /**
     * Forwards each client byte once, in order, and ACKs it. Nothing was ACKed
     * before, so the device kept retransmitting and every retransmit was written
     * to the server again, corrupting the stream.
     */
    @Synchronized
    fun onClientData(seq: Long, data: ByteArray) {
        if (clientFinished) { sendAck(); return }
        val alreadyAcked = (clientSeq - seq) and 0xFFFFFFFFL
        val fresh = when {
            alreadyAcked == 0L -> data
            alreadyAcked < 0x80000000L && alreadyAcked < data.size ->
                data.copyOfRange(alreadyAcked.toInt(), data.size)
            else -> { sendAck(); return }   // duplicate, or a gap: re-ACK what we have
        }
        helloBuf?.let { buf ->
            val joined = buf + fresh
            val name = com.privacyguard.core.tls.ClientHelloParser.parse(joined)?.sni
            helloBuf = if (name != null || joined.size >= 4096) null else joined
            if (name != null) sni = name
            // DoH by name over IPv6 skipped DnsBypassGuard (only IPv4 checked it).
            if (com.privacyguard.vpn.firewall.DnsBypassGuard.isDohHost(name)) {
                close()
                return
            }
        }
        val ok = runCatching {
            val bb = ByteBuffer.wrap(fresh)
            while (bb.hasRemaining()) channel.write(bb)   // blocking channel
        }.isSuccess
        if (!ok) { close(); return }
        sent.addAndGet(fresh.size.toLong())
        clientSeq = (clientSeq + fresh.size) and 0xFFFFFFFFL
        sendAck()
    }

    /** Client half-close: stop sending upstream but keep relaying the response. */
    @Synchronized
    fun onClientFin(seq: Long, dataLen: Int) {
        if (clientFinished) { sendAck(); return }
        // Only once every byte before the FIN arrived; otherwise the device resends.
        if (((seq + dataLen) and 0xFFFFFFFFL) != clientSeq) { sendAck(); return }
        clientFinished = true
        clientSeq = (clientSeq + 1) and 0xFFFFFFFFL
        runCatching { channel.shutdownOutput() }
        sendAck()
    }

    fun startRelay() {
        val buf = ByteBuffer.allocate(8192)
        while (channel.isOpen) {
            buf.clear()
            val n = runCatching { channel.read(buf) }.getOrDefault(-1)
            if (n <= 0) break
            buf.flip()
            val bytes = ByteArray(n).also { buf.get(it) }
            received.addAndGet(n.toLong())
            var offset = 0
            while (offset < n) {
                val end = minOf(offset + MSS, n)
                val pkt = Ipv6Packet.buildTcp(
                    srcAddr = srcIp, srcPort = srcPort,
                    dstAddr = dstIp, dstPort = dstPort,
                    seqNum = serverSeq, ackNum = clientSeq,
                    ack = true, psh = end == n,
                    data = bytes.copyOfRange(offset, end),
                )
                serverSeq = (serverSeq + (end - offset)) and 0xFFFFFFFFL
                tunWriter.enqueue(pkt)
                offset = end
            }
        }
        // Send FIN-ACK to device
        val fin = Ipv6Packet.buildTcp(
            srcAddr = srcIp, srcPort = srcPort,
            dstAddr = dstIp, dstPort = dstPort,
            seqNum = serverSeq, ackNum = clientSeq,
            fin = true, ack = true,
        )
        tunWriter.enqueue(fin)
        close()
    }

    fun close() {
        runCatching { channel.close() }
        if (reported.compareAndSet(false, true)) {
            runCatching {
                onClosed?.invoke(Ipv6Flow(dstIp, dstPort, srcIp, srcPort, "TCP", startedAt, sent.get(), received.get(), sni))
            }
        }
    }
}

// ── UDP relay ─────────────────────────────────────────────────────────────────

private class UdpRelay(
    val socket:            DatagramSocket,
    private val srcIp:     String,
    private val srcPort:   Int,
    private val dstIp:     String,
    private val dstPort:   Int,
    private val tunWriter: TunWriter,
    private val relayKey:  String,
    private val relays:    ConcurrentHashMap<String, UdpRelay>,
    private val onClosed:  ((Ipv6Flow) -> Unit)? = null,
) {
    private val startedAt = System.currentTimeMillis()
    private val sent = java.util.concurrent.atomic.AtomicLong()
    private val received = java.util.concurrent.atomic.AtomicLong()

    fun send(data: ByteArray, host: String, port: Int) {
        sent.addAndGet(data.size.toLong())
        runCatching {
            val addr = Inet6Address.getByName(host)
            socket.send(DatagramPacket(data, data.size, addr, port))
        }
    }

    fun startReceiveLoop() {
        val buf = ByteArray(65_535)
        try {
            while (!socket.isClosed) {
                val dp = DatagramPacket(buf, buf.size)
                socket.receive(dp)   // throws SocketTimeoutException when soTimeout fires
                val data = dp.data.copyOf(dp.length)
                received.addAndGet(dp.length.toLong())
                tunWriter.enqueue(Ipv6Packet.buildUdp(
                    srcAddr = dstIp, srcPort = dstPort,
                    dstAddr = srcIp, dstPort = srcPort,
                    data    = data,
                ))
            }
        } catch (_: java.net.SocketTimeoutException) {
            Log.d("Ipv6Proxy", "UDP relay $srcIp:$srcPort→$dstIp:$dstPort idle timeout, closing")
        } catch (_: Exception) {
            // socket closed externally or I/O error — normal teardown
        } finally {
            socket.close()
            relays.remove(relayKey, this)
            runCatching {
                onClosed?.invoke(Ipv6Flow(srcIp, srcPort, dstIp, dstPort, "UDP", startedAt, sent.get(), received.get(), null))
            }
        }
    }
}

/** One finished IPv6 flow, from the device's point of view. */
data class Ipv6Flow(
    val sourceIp: String,
    val sourcePort: Int,
    val destinationIp: String,
    val destinationPort: Int,
    val protocol: String,
    val startedAt: Long,
    val bytesSent: Long,
    val bytesReceived: Long,
    val sni: String?,
)