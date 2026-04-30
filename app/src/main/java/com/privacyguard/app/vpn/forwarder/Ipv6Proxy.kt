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
 */
class Ipv6Proxy(
    private val tunWriter: TunWriter,
    private val protectSocket: (java.net.Socket) -> Boolean,
    private val protectUdpSocket: (java.net.DatagramSocket) -> Boolean,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "Ipv6Proxy"
        private const val RELAY_TIMEOUT_MS = 30_000L
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
                val data    = payload.copyOfRange(off + 8, (off + udpLen).coerceAtMost(payload.size))
                handleUdp(ipv6, srcPort, dstPort, data)
            }
            else -> Log.d(TAG, "Unsupported IPv6 next header: ${ipv6.nextHeader}")
        }
    }

    // ── TCP ───────────────────────────────────────────────────────────────────

    private fun handleTcp(ipv6: Ipv6Packet, tcp: TcpPacket) {
        val key = SessionKey.of(ipv6.sourceIp, tcp.sourcePort, ipv6.destinationIp, tcp.destinationPort, 6)

        if (tcp.isSyn) {
            openTcpSession(key, ipv6, tcp)
            return
        }

        val session = tcpSessions[key] ?: return
        when {
            tcp.flagFin || tcp.flagRst -> {
                session.close()
                tcpSessions.remove(key)
            }
            tcp.data.isNotEmpty() -> session.sendToRemote(tcp.data)
        }
    }

    private fun openTcpSession(key: SessionKey, ipv6: Ipv6Packet, tcp: TcpPacket) {
        scope.launch(Dispatchers.IO) {
            try {
                val channel = SocketChannel.open()
                protectSocket(channel.socket())
                channel.connect(InetSocketAddress(
                    Inet6Address.getByName(ipv6.destinationIp),
                    tcp.destinationPort,
                ))

                val session = TcpRelaySession(
                    channel        = channel,
                    srcIp          = ipv6.destinationIp,
                    srcPort        = tcp.destinationPort,
                    dstIp          = ipv6.sourceIp,
                    dstPort        = tcp.sourcePort,
                    serverSeqInit  = (Math.random() * Int.MAX_VALUE).toLong(),
                    clientSeqInit  = tcp.sequenceNumber + 1,
                    tunWriter      = tunWriter,
                )
                tcpSessions[key] = session

                // Send SYN-ACK back to device
                session.sendSynAck()

                // Start relay loop
                session.startRelay()
            } catch (e: Exception) {
                Log.e(TAG, "IPv6 TCP connect failed: ${e.message}")
                sendRst(ipv6, tcp)
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
) {
    private var serverSeq = serverSeqInit
    private var clientSeq = clientSeqInit
    fun sendSynAck() {
        val pkt = Ipv6Packet.buildTcp(
            srcAddr = srcIp, srcPort = srcPort,
            dstAddr = dstIp, dstPort = dstPort,
            seqNum = serverSeq, ackNum = clientSeq,
            syn = true, ack = true,
        )
        serverSeq++
        tunWriter.enqueue(pkt)
    }

    fun sendToRemote(data: ByteArray) {
        runCatching { channel.write(ByteBuffer.wrap(data)) }
    }

    fun startRelay() {
        val buf = ByteBuffer.allocate(8192)
        while (channel.isOpen) {
            buf.clear()
            val n = runCatching { channel.read(buf) }.getOrDefault(-1)
            if (n <= 0) break
            buf.flip()
            val bytes = ByteArray(n).also { buf.get(it) }
            val pkt = Ipv6Packet.buildTcp(
                srcAddr = srcIp, srcPort = srcPort,
                dstAddr = dstIp, dstPort = dstPort,
                seqNum = serverSeq, ackNum = clientSeq,
                ack = true, psh = true,
                data = bytes,
            )
            serverSeq += n
            tunWriter.enqueue(pkt)
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

    fun close() { runCatching { channel.close() } }
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
) {
    fun send(data: ByteArray, host: String, port: Int) {
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
                tunWriter.enqueue(Ipv6Packet.buildUdp(
                    srcAddr = dstIp, srcPort = dstPort,
                    dstAddr = srcIp, dstPort = srcPort,
                    data    = data,
                ))
            }
        } catch (_: java.net.SocketTimeoutException) {
            Log.d(TAG, "UDP relay $srcIp:$srcPort→$dstIp:$dstPort idle timeout, closing")
        } catch (_: Exception) {
            // socket closed externally or I/O error — normal teardown
        } finally {
            socket.close()
            relays.remove(relayKey, this)
        }
    }
}
