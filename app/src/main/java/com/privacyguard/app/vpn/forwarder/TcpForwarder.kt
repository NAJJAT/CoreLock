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
import com.privacyguard.app.data.db.PayloadLogEntity
import com.privacyguard.domain.repository.PayloadLogRepository
import com.privacyguard.vpn.inspector.EncryptionEnforcer
import com.privacyguard.vpn.mitm.MitmConfig
import com.privacyguard.vpn.mitm.MitmEngine
import com.privacyguard.vpn.mitm.PayloadParser
import com.privacyguard.vpn.mitm.PayloadShipper
import com.privacyguard.vpn.mitm.PinningDetector
import com.privacyguard.vpn.tunnel.TunWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    // MITM dependencies
    private val mitmEngine: MitmEngine,
    private val pinningDetector: PinningDetector,
    private val mitmConfig: MitmConfig,
    private val payloadParser: PayloadParser,
    private val payloadShipper: PayloadShipper,
    private val payloadLogRepository: PayloadLogRepository
) : Runnable {

    companion object {
        private const val TAG = "TcpForwarder"
        private const val BUFFER_SIZE = 32_767
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
        try {
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            protectSocket(channel.socket())
            channel.connect(InetSocketAddress(ip.destinationIp, tcp.destinationPort))

            session.tcpChannel = channel
            session.lastDeviceSeq = tcp.sequenceNumber
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

        if (!session.encryptionClassified) {
            val result = encEnforcer.inspect(ip, tcp)
            session.encryptionStatus = result.encryptionStatus
            session.tlsVersion = result.tlsVersion
            session.encryptionClassified = true
            if (result.sniHostname != null) session.tlsSni = result.sniHostname

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
                )
                if (decision.isBlocked) {
                    Log.d(TAG, "Blocked cleartext: ${key.destinationIp}:${key.destinationPort}")
                    encryptionBlocked.incrementAndGet()
                    sendRstToDevice(ip, tcp)
                    sessionTable.remove(key)
                    return
                }
            }
        }

        // MITM INTERCEPTION HOOK
        // ==================== MITM INTERCEPTION HOOK ====================
        Log.d(TAG, "🔍 MITM CHECK: port=${session.key.destinationPort}, " +
                "sni=${session.tlsSni}, " +
                "mitmEnabled=${mitmConfig.isEnabled}, " +
                "MITM_AVAILABLE=${BuildConfig.MITM_AVAILABLE}")

        if (BuildConfig.MITM_AVAILABLE &&
            session.key.destinationPort == 443 &&
            session.tlsSni != null &&
            mitmConfig.isEnabled &&
            !pinningDetector.isPinned(session.ownerPackage, session.tlsSni)) {

            Log.d(TAG, "✅ MITM CONDITIONS MET - Intercepting: ${session.tlsSni}")
            mitmInterceptedCount.incrementAndGet()

            val success = mitmEngine.intercept(session) { direction, bytes, sess ->
                processPayload(direction, bytes, sess)
            }

            if (success) {
                Log.d(TAG, "✅ MITM intercept SUCCESS for ${session.tlsSni}")
                return
            } else {
                Log.e(TAG, "❌ MITM intercept FAILED for ${session.tlsSni}")
            }
        } else {
            Log.d(TAG, "❌ MITM CONDITIONS NOT MET: " +
                    "portCheck=${session.key.destinationPort == 443}, " +
                    "sniNotNull=${session.tlsSni != null}, " +
                    "enabled=${mitmConfig.isEnabled}, " +
                    "pinned=${pinningDetector.isPinned(session.ownerPackage, session.tlsSni)}")
        }
// ==================== END MITM HOOK ====================
    }


    private fun processPayload(direction: String, bytes: ByteArray, session: Session) {
        try {
            val parsed = payloadParser.parse(bytes, direction, session)

            // FIXED: Proper JSON serialization for headers map
            val headersJson = try {
                Json.encodeToString(parsed.headers)
            } catch (e: Exception) {
                "{}"
            }

            val entity = PayloadLogEntity(
                timestamp = parsed.timestamp,
                sessionId = session.key.toString(),
                direction = parsed.direction,
                ownerPackage = session.ownerPackage,
                sniHostname = session.tlsSni,
                destinationIp = session.key.destinationIp,
                destinationPort = session.key.destinationPort,
                protocol = parsed.protocol.name,
                method = parsed.method,
                urlPath = parsed.urlPath,
                headers = headersJson,
                body = parsed.body,
                bodyEncoding = parsed.bodyEncoding,
                sizeBytes = parsed.sizeBytes,
                piiRedacted = parsed.piiRedacted,
                isMitmSuccess = true
            )

            coroutineScope.launch {
                payloadLogRepository.saveLog(entity)
            }

            payloadShipper.enqueue(
                parsed,
                session.key.toString(),
                session.key.destinationIp,
                session.key.destinationPort,
                session.ownerPackage,
                session.tlsSni
            )

            session.payloadCount.incrementAndGet()
            Log.d(TAG, "MITM payload processed: ${parsed.protocol} ${parsed.sizeBytes} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process MITM payload", e)
        }
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
                sendSynAckToDevice(session)
                Log.d(TAG, "Connected → SYN-ACK sent for ${session.key}")
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
        injectDataToDevice(session, data)
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
        tunWriter.enqueueWithChecksums(
            buildTcp(
                srcIp = session.key.destinationIp,
                srcPort = session.key.destinationPort,
                dstIp = session.key.sourceIp,
                dstPort = session.key.sourcePort,
                seqNum = session.sendSeq,
                ackNum = session.lastAckToDevice,
                ack = true,
                psh = true,
                data = data,
            )
        )
        session.sendSeq += data.size
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