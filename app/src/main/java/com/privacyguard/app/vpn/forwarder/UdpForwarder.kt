package com.privacyguard.vpn.forwarder

import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.core.session.SessionTable
import com.privacyguard.core.utils.Checksum
import com.privacyguard.vpn.tunnel.TunWriter
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Proxies UDP datagrams on behalf of the device.
 *
 * UDP is connectionless so the forwarder maintains a per-(src, dst) pseudo-session
 * in [SessionTable] purely for lifetime tracking and traffic accounting.
 *
 * For each unique (srcIp, srcPort, dstIp, dstPort) tuple:
 *  1. A [DatagramChannel] is opened and protected from VPN re-interception.
 *  2. The datagram payload is forwarded to the remote server.
 *  3. Responses from the remote server are read by the selector loop and
 *     injected back into the TUN as UDP packets addressed to the device.
 *
 * Sessions time out after [SessionTable.udpTimeoutMs] of inactivity — the
 * reaper in [SessionTable] handles cleanup.
 */
class UdpForwarder(
    private val sessionTable: SessionTable,
    private val tunWriter:    TunWriter,
    private val protectSocket: (java.net.Socket) -> Boolean,
) : Runnable {

    // ─────────────────────────────────────────────────────────────────────────
    // Selector
    // ─────────────────────────────────────────────────────────────────────────

    private val selector = Selector.open()
    private val running  = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(this, "udp-selector").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        selector.wakeup()
        thread?.join(2_000)
        selector.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry Point — called from TunReader thread
    // ─────────────────────────────────────────────────────────────────────────

    fun handle(ip: IpPacket, udp: UdpPacket) {
        val key = SessionKey.of(
            ip.sourceIp, udp.sourcePort,
            ip.destinationIp, udp.destinationPort,
            IpPacket.PROTO_UDP,
        )

        val session = sessionTable.get(key) ?: createSession(ip, udp, key) ?: return
        session.recordOutbound(udp.data.size)
        forwardedBytesOut.addAndGet(udp.data.size.toLong())

        val channel = session.udpChannel ?: return
        try {
            val buf = ByteBuffer.wrap(udp.data)
            channel.send(buf, InetSocketAddress(ip.destinationIp, udp.destinationPort))
        } catch (e: Exception) {
            System.err.println("[UdpForwarder] send error for $key: ${e.message}")
            sessionTable.remove(key)
            errorCount.incrementAndGet()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session Creation
    // ─────────────────────────────────────────────────────────────────────────

    private fun createSession(ip: IpPacket, udp: UdpPacket, key: SessionKey): Session? {
        return try {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)
            channel.socket().also { protectSocket(it as java.net.Socket) }
            channel.bind(null)   // Let OS pick ephemeral port

            val session = sessionTable.getOrCreate(key)
            session.udpChannel = channel

            // Register with selector for readability
            selector.wakeup()
            val selKey = channel.register(selector, SelectionKey.OP_READ)
            selKey.attach(session)
            session.selectionKey = selKey

            session
        } catch (e: Exception) {
            System.err.println("[UdpForwarder] create session error for $key: ${e.message}")
            errorCount.incrementAndGet()
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Selector Loop — reads responses from remote servers
    // ─────────────────────────────────────────────────────────────────────────

    override fun run() {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)

        while (running.get()) {
            try {
                val ready = selector.select(SELECT_TIMEOUT_MS)
                if (ready == 0) continue

                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val selKey = keys.next()
                    keys.remove()

                    if (!selKey.isReadable) continue
                    val session = selKey.attachment() as? Session ?: continue

                    try {
                        readFromRemote(selKey, session, buffer)
                    } catch (e: Exception) {
                        System.err.println("[UdpForwarder] read error: ${e.message}")
                        sessionTable.remove(session.key)
                        errorCount.incrementAndGet()
                    }
                }
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read Response → Inject into TUN
    // ─────────────────────────────────────────────────────────────────────────

    private fun readFromRemote(selKey: SelectionKey, session: Session, buffer: ByteBuffer) {
        val channel = selKey.channel() as DatagramChannel
        buffer.clear()

        val remoteAddr = channel.receive(buffer) as? InetSocketAddress ?: return
        buffer.flip()

        val n = buffer.remaining()
        if (n == 0) return

        val data = ByteArray(n).also { buffer.get(it) }
        session.recordInbound(n)
        forwardedBytesIn.addAndGet(n.toLong())

        // Build a UDP response packet addressed to the device
        val packet = buildUdpPacket(
            srcIp   = session.key.destinationIp,
            srcPort = session.key.destinationPort,
            dstIp   = session.key.sourceIp,
            dstPort = session.key.sourcePort,
            data    = data,
        )
        tunWriter.enqueueWithChecksums(packet)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Packet Builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildUdpPacket(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        data: ByteArray,
    ): ByteArray {
        val udpLen   = 8 + data.size
        val totalLen = 20 + udpLen
        val pkt      = ByteArray(totalLen)

        // IPv4 header
        pkt[0]  = 0x45.toByte()
        pkt[2]  = (totalLen ushr 8).toByte()
        pkt[3]  = (totalLen and 0xFF).toByte()
        pkt[8]  = 64.toByte()      // TTL
        pkt[9]  = 17.toByte()      // UDP
        writeIp(pkt, 12, srcIp)
        writeIp(pkt, 16, dstIp)

        // UDP header
        val u = 20
        pkt[u]   = (srcPort ushr 8).toByte(); pkt[u+1] = (srcPort and 0xFF).toByte()
        pkt[u+2] = (dstPort ushr 8).toByte(); pkt[u+3] = (dstPort and 0xFF).toByte()
        pkt[u+4] = (udpLen  ushr 8).toByte(); pkt[u+5] = (udpLen  and 0xFF).toByte()
        // checksum fixed after

        if (data.isNotEmpty()) data.copyInto(pkt, 28)

        Checksum.setIpv4HeaderChecksum(pkt, 0)
        Checksum.setUdpChecksum(pkt, 0)
        return pkt
    }

    private fun writeIp(buf: ByteArray, offset: Int, ip: String) {
        val parts = ip.split('.')
        for (i in 0..3) buf[offset + i] = (parts[i].toInt() and 0xFF).toByte()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics
    // ─────────────────────────────────────────────────────────────────────────

    val forwardedBytesIn  = AtomicLong(0)
    val forwardedBytesOut = AtomicLong(0)
    val errorCount        = AtomicLong(0)

    companion object {
        private const val BUFFER_SIZE       = 32_767
        private const val SELECT_TIMEOUT_MS = 100L
    }
}