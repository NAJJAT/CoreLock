package com.privacyguard.vpn.forwarder

import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.core.session.SessionTable
import com.privacyguard.core.utils.Checksum
import com.privacyguard.vpn.tunnel.TunWriter
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Proxies UDP datagrams on behalf of the device.
 * 
 * FIXED: Added proper error handling, logging, and ensured packet forwarding.
 */
class UdpForwarder(
    private val sessionTable: SessionTable,
    private val tunWriter: TunWriter,
    private val protectSocket: (DatagramSocket) -> Boolean,
) : Runnable {

    companion object {
        private const val TAG = "UdpForwarder"
        private const val BUFFER_SIZE = 32767
        private const val SELECT_TIMEOUT_MS = 100L
    }

    private val selector = Selector.open()
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    val forwardedBytesIn = AtomicLong(0)
    val forwardedBytesOut = AtomicLong(0)
    val errorCount = AtomicLong(0)

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(this, "udp-selector").also {
            it.isDaemon = true
            it.start()
        }
        Log.d(TAG, "UDP Forwarder started")
    }

    fun stop() {
        running.set(false)
        selector.wakeup()
        thread?.join(2000)
        selector.close()
        Log.d(TAG, "UDP Forwarder stopped")
    }

    fun handle(ip: IpPacket, udp: UdpPacket, ownerUid: Int = -1, ownerPackage: String? = null) {
        val key = SessionKey.of(
            ip.sourceIp, udp.sourcePort,
            ip.destinationIp, udp.destinationPort,
            IpPacket.PROTO_UDP,
        )

        Log.d(TAG, "UDP packet: ${udp.sourcePort} -> ${udp.destinationPort} data=${udp.data.size}")

        val session = sessionTable.get(key) ?: createSession(ip, udp, key, ownerUid, ownerPackage) ?: return
        
        if (session.ownerUid == -1 && ownerUid >= 0) {
            session.ownerUid = ownerUid
            session.ownerPackage = ownerPackage
        }
        
        session.recordOutbound(udp.data.size)
        forwardedBytesOut.addAndGet(udp.data.size.toLong())

        val channel = session.udpChannel
        if (channel == null) {
            Log.w(TAG, "UDP channel is null for $key")
            return
        }
        
        try {
            val buf = ByteBuffer.wrap(udp.data)
            val sent = channel.send(buf, InetSocketAddress(ip.destinationIp, udp.destinationPort))
            Log.d(TAG, "UDP packet sent: $sent bytes to ${ip.destinationIp}:${udp.destinationPort}")
        } catch (e: Exception) {
            Log.e(TAG, "UDP send error for $key: ${e.message}")
            sessionTable.remove(key)
            errorCount.incrementAndGet()
        }
    }

    private fun createSession(ip: IpPacket, udp: UdpPacket, key: SessionKey, ownerUid: Int, ownerPackage: String?): Session? {
        return try {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)
            channel.socket().also { protectSocket(it) }
            channel.bind(null)

            val session = sessionTable.getOrCreate(key, uid = ownerUid, ownerPackage = ownerPackage)
            session.udpChannel = channel

            selector.wakeup()
            val selKey = channel.register(selector, SelectionKey.OP_READ, session)
            session.selectionKey = selKey

            Log.d(TAG, "UDP session created for $key")
            session
        } catch (e: Exception) {
            Log.e(TAG, "UDP session creation error for $key: ${e.message}")
            errorCount.incrementAndGet()
            null
        }
    }

    override fun run() {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        Log.d(TAG, "UDP selector loop started")

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
                        Log.e(TAG, "UDP read error: ${e.message}")
                        sessionTable.remove(session.key)
                        errorCount.incrementAndGet()
                    }
                }
            } catch (e: Exception) {
                if (!running.get()) break
                Log.e(TAG, "UDP selector error: ${e.message}")
            }
        }
        
        Log.d(TAG, "UDP selector loop ended")
    }

    private fun readFromRemote(selKey: SelectionKey, session: Session, buffer: ByteBuffer) {
        val channel = selKey.channel() as DatagramChannel
        buffer.clear()

        val remoteAddr = channel.receive(buffer) as? InetSocketAddress ?: return
        buffer.flip()

        val n = buffer.remaining()
        if (n == 0) return

        val data = ByteArray(n)
        buffer.get(data)
        
        session.recordInbound(n)
        forwardedBytesIn.addAndGet(n.toLong())

        val packet = buildUdpPacket(
            srcIp = session.key.destinationIp,
            srcPort = session.key.destinationPort,
            dstIp = session.key.sourceIp,
            dstPort = session.key.sourcePort,
            data = data,
        )
        
        tunWriter.enqueueWithChecksums(packet)
        Log.d(TAG, "UDP response forwarded: $n bytes to device")
    }

    private fun buildUdpPacket(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        data: ByteArray,
    ): ByteArray {
        val udpLen = 8 + data.size
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)

        // IPv4 header
        pkt[0] = 0x45
        pkt[2] = ((totalLen shr 8) and 0xFF).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        pkt[8] = 64
        pkt[9] = 17
        
        // Source IP
        val srcParts = srcIp.split('.')
        for (i in 0..3) pkt[12 + i] = srcParts[i].toInt().toByte()
        
        // Destination IP
        val dstParts = dstIp.split('.')
        for (i in 0..3) pkt[16 + i] = dstParts[i].toInt().toByte()

        // UDP header
        pkt[20] = ((srcPort shr 8) and 0xFF).toByte()
        pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = ((dstPort shr 8) and 0xFF).toByte()
        pkt[23] = (dstPort and 0xFF).toByte()
        pkt[24] = ((udpLen shr 8) and 0xFF).toByte()
        pkt[25] = (udpLen and 0xFF).toByte()

        if (data.isNotEmpty()) {
            data.copyInto(pkt, 28)
        }

        Checksum.setIpv4HeaderChecksum(pkt, 0)
        Checksum.setUdpChecksum(pkt, 0)
        
        return pkt
    }
}
