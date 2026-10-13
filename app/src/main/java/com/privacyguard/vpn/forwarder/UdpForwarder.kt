package com.privacyguard.vpn.forwarder

import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.packet.Ipv6Packet
import com.privacyguard.core.packet.UdpPacket
import com.privacyguard.core.session.Session
import com.privacyguard.core.session.SessionKey
import com.privacyguard.core.session.SessionTable
import com.privacyguard.core.utils.Checksum
import com.privacyguard.vpn.tunnel.TunWriter
import java.io.IOException
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
 * Proxies UDP datagrams (IPv4 and IPv6) on behalf of the device, one connected,
 * protected channel per flow, all served by a single selector thread.
 */
class UdpForwarder(
    private val sessionTable: SessionTable,
    private val tunWriter: TunWriter,
    private val protectSocket: (DatagramSocket) -> Boolean,
) : Runnable {

    companion object {
        private const val TAG = "UdpForwarder"
        // Largest UDP payload; a smaller buffer silently truncated big datagrams.
        private const val BUFFER_SIZE = 65_535
        private const val SELECT_TIMEOUT_MS = 100L
        /** Must match VpnService.Builder.setMtu. */
        const val TUN_MTU = 1500

        /**
         * Splits an IPv4 packet larger than [mtu] into fragments the device's stack
         * reassembles. The transport checksum must already be set on [packet].
         */
        internal fun fragmentIpv4(packet: ByteArray, mtu: Int, id: Int): List<ByteArray> {
            if (packet.size <= mtu) return listOf(packet)
            val headerLen = (packet[0].toInt() and 0x0F) * 4
            val payload = packet.size - headerLen
            val chunk = (mtu - headerLen) / 8 * 8   // fragment offsets count 8-byte units
            val fragments = ArrayList<ByteArray>((payload + chunk - 1) / chunk)
            var offset = 0
            while (offset < payload) {
                val len = minOf(chunk, payload - offset)
                val more = offset + len < payload
                val frag = ByteArray(headerLen + len)
                packet.copyInto(frag, 0, 0, headerLen)
                packet.copyInto(frag, headerLen, headerLen + offset, headerLen + offset + len)
                val total = frag.size
                frag[2] = (total ushr 8).toByte()
                frag[3] = (total and 0xFF).toByte()
                frag[4] = (id ushr 8).toByte()
                frag[5] = (id and 0xFF).toByte()
                val units = offset / 8
                frag[6] = (((if (more) 0x20 else 0) or ((units ushr 8) and 0x1F))).toByte()
                frag[7] = (units and 0xFF).toByte()
                Checksum.setIpv4HeaderChecksum(frag, 0)
                fragments += frag
                offset += len
            }
            return fragments
        }

        /**
         * Splits an IPv6 packet (fixed header + transport) larger than [mtu] using a
         * Fragment extension header (RFC 8200 §4.5).
         */
        internal fun fragmentIpv6(packet: ByteArray, mtu: Int, id: Int): List<ByteArray> {
            if (packet.size <= mtu) return listOf(packet)
            val h = Ipv6Packet.HEADER_LEN
            val nextHeader = packet[6]
            val payload = packet.size - h
            val chunk = (mtu - h - 8) / 8 * 8
            val fragments = ArrayList<ByteArray>((payload + chunk - 1) / chunk)
            var offset = 0
            while (offset < payload) {
                val len = minOf(chunk, payload - offset)
                val more = offset + len < payload
                val frag = ByteArray(h + 8 + len)
                packet.copyInto(frag, 0, 0, h)
                val plen = 8 + len
                frag[4] = (plen ushr 8).toByte()
                frag[5] = (plen and 0xFF).toByte()
                frag[6] = 44   // Fragment header
                frag[h] = nextHeader
                val offWord = offset or (if (more) 1 else 0)   // offset is a multiple of 8
                frag[h + 2] = (offWord ushr 8).toByte()
                frag[h + 3] = (offWord and 0xFF).toByte()
                frag[h + 4] = (id ushr 24).toByte()
                frag[h + 5] = (id ushr 16).toByte()
                frag[h + 6] = (id ushr 8).toByte()
                frag[h + 7] = id.toByte()
                packet.copyInto(frag, h + 8, h + offset, h + offset + len)
                fragments += frag
                offset += len
            }
            return fragments
        }
    }

    private val selector = Selector.open()
    /** Registrations performed by the selector thread (see createSession). */
    private val pendingRegistrations = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    private var fragmentId = 1

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

    fun handle(ip: IpPacket, udp: UdpPacket, ownerUid: Int = -1, ownerPackage: String? = null) =
        handle(SessionKey.of(ip.sourceIp, udp.sourcePort, ip.destinationIp, udp.destinationPort, IpPacket.PROTO_UDP),
            udp.data, ownerUid, ownerPackage)

    /** One device datagram for the flow [key] (TUN reader thread). */
    fun handle(key: SessionKey, data: ByteArray, ownerUid: Int = -1, ownerPackage: String? = null) {
        val session = sessionTable.get(key) ?: createSession(key, ownerUid, ownerPackage) ?: return

        if (session.ownerUid == -1 && ownerUid >= 0) {
            session.ownerUid = ownerUid
            session.ownerPackage = ownerPackage
        }

        session.recordOutbound(data.size)
        forwardedBytesOut.addAndGet(data.size.toLong())

        val channel = session.udpChannel
        if (channel == null) {
            Log.w(TAG, "UDP channel is null for $key")
            return
        }

        try {
            // Connected channel: write() goes to the flow's own destination.
            channel.write(ByteBuffer.wrap(data))
        } catch (e: Exception) {
            Log.e(TAG, "UDP send error for $key: ${e.message}")
            sessionTable.remove(key)
            errorCount.incrementAndGet()
        }
    }

    private fun createSession(key: SessionKey, ownerUid: Int, ownerPackage: String?): Session? {
        var channel: DatagramChannel? = null
        return try {
            channel = DatagramChannel.open()
            channel.configureBlocking(false)
            // Unprotected, the datagrams would come straight back into the tunnel.
            if (!protectSocket(channel.socket())) throw IOException("protect() failed")
            channel.bind(null)
            // Connected: the kernel then only delivers datagrams from this destination.
            // Unconnected, anyone who found the port could inject datagrams that were
            // handed to the app as if they came from the server it talks to.
            channel.connect(InetSocketAddress(key.destinationIp, key.destinationPort))

            val session = sessionTable.getOrCreate(key, uid = ownerUid, ownerPackage = ownerPackage)
            session.udpChannel = channel

            // Registered by the selector thread: register() from this (TUN reader)
            // thread blocked behind select() and stalled every packet behind it.
            val ch = channel
            pendingRegistrations.add(Runnable {
                if (session.isClosed || !ch.isOpen) return@Runnable
                runCatching { session.selectionKey = ch.register(selector, SelectionKey.OP_READ, session) }
                    .onFailure { sessionTable.remove(key) }
            })
            selector.wakeup()

            session
        } catch (e: Exception) {
            Log.e(TAG, "UDP session creation error: ${e.message}")
            runCatching { channel?.close() }
            sessionTable.get(key)?.takeIf { it.udpChannel == null || it.udpChannel === channel }
                ?.let { sessionTable.remove(key) }
            errorCount.incrementAndGet()
            null
        }
    }

    override fun run() {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)
        Log.d(TAG, "UDP selector loop started")

        while (running.get()) {
            try {
                while (true) { (pendingRegistrations.poll() ?: break).run() }
                val ready = selector.select(SELECT_TIMEOUT_MS)
                if (ready == 0) continue

                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val selKey = keys.next()
                    keys.remove()

                    if (!selKey.isValid || !selKey.isReadable) continue
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

        channel.receive(buffer) ?: return
        buffer.flip()

        val n = buffer.remaining()
        if (n == 0) return

        val data = ByteArray(n)
        buffer.get(data)

        session.recordInbound(n)
        forwardedBytesIn.addAndGet(n.toLong())

        val key = session.key
        val id = nextFragmentId()
        val packets = if (key.sourceIp.contains(':')) {
            fragmentIpv6(Ipv6Packet.buildUdp(
                srcAddr = key.destinationIp, srcPort = key.destinationPort,
                dstAddr = key.sourceIp, dstPort = key.sourcePort,
                data = data,
            ), TUN_MTU, id)
        } else {
            fragmentIpv4(buildUdpPacket(
                srcIp = key.destinationIp, srcPort = key.destinationPort,
                dstIp = key.sourceIp, dstPort = key.sourcePort,
                data = data,
            ), TUN_MTU, id)
        }
        // Checksums are final here (fragments must not have the UDP checksum redone).
        packets.forEach { tunWriter.enqueue(it) }
    }

    private fun nextFragmentId(): Int {
        fragmentId = if (fragmentId >= 0xFFFF) 1 else fragmentId + 1
        return fragmentId
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
