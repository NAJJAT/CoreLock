/**
 * Session.kt
 *
 * Represents an active network connection session
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.session

import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class Session(
    val key: SessionKey,
    val socket: Socket,
    val creationTime: Long = System.currentTimeMillis(),
    var appUid: Int = -1,
    var appName: String = "Unknown"
) {

    @Volatile
    var lastActivityTime: Long = creationTime
        private set

    private val _bytesSent = AtomicLong(0)
    val bytesSent: Long get() = _bytesSent.get()

    private val _bytesReceived = AtomicLong(0)
    val bytesReceived: Long get() = _bytesReceived.get()

    private val _packetsSent = AtomicLong(0)
    val packetsSent: Long get() = _packetsSent.get()

    private val _packetsReceived = AtomicLong(0)
    val packetsReceived: Long get() = _packetsReceived.get()

    // TCP-specific state
    @Volatile
    var tcpState: TcpState? = null

    @Volatile
    var tcpClientSeq: Long? = null

    @Volatile
    var tcpClientAck: Long? = null

    @Volatile
    var tcpServerSeq: Long? = null

    @Volatile
    var tcpServerAck: Long? = null

    private val pendingClientData = ByteArrayOutputStream()
    private val pendingServerData = ByteArrayOutputStream()

    @Volatile
    var closeReason: String? = null

    fun isTcp(): Boolean = key.isTcp()

    fun isUdp(): Boolean = key.isUdp()

    fun recordOutgoing(bytes: Int) {
        lastActivityTime = System.currentTimeMillis()
        _bytesSent.addAndGet(bytes.toLong())
        _packetsSent.incrementAndGet()
    }

    fun recordIncoming(bytes: Int) {
        lastActivityTime = System.currentTimeMillis()
        _bytesReceived.addAndGet(bytes.toLong())
        _packetsReceived.incrementAndGet()
    }

    fun bufferClientData(data: ByteArray) {
        synchronized(pendingClientData) { pendingClientData.write(data) }
    }

    fun flushClientBuffer(): ByteArray {
        synchronized(pendingClientData) {
            val data = pendingClientData.toByteArray()
            pendingClientData.reset()
            return data
        }
    }

    fun bufferServerData(data: ByteArray) {
        synchronized(pendingServerData) { pendingServerData.write(data) }
    }

    fun flushServerBuffer(): ByteArray {
        synchronized(pendingServerData) {
            val data = pendingServerData.toByteArray()
            pendingServerData.reset()
            return data
        }
    }

    fun isIdle(tcpTimeoutMillis: Long = 30000, udpTimeoutMillis: Long = 10000): Boolean {
        val timeout = if (isTcp()) tcpTimeoutMillis else udpTimeoutMillis
        return System.currentTimeMillis() - lastActivityTime > timeout
    }

    fun close(reason: String = "normal") {
        closeReason = reason
        try {
            if (!socket.isClosed) socket.close()
        } catch (e: Exception) { }
        synchronized(pendingClientData) { pendingClientData.reset() }
        synchronized(pendingServerData) { pendingServerData.reset() }
    }

    fun snapshot(): SessionSnapshot {
        return SessionSnapshot(
            key = key,
            creationTime = creationTime,
            lastActivityTime = lastActivityTime,
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            packetsSent = packetsSent,
            packetsReceived = packetsReceived,
            tcpState = tcpState?.name,
            appUid = appUid,
            appName = appName,
            closeReason = closeReason
        )
    }

    val ageSeconds: Long get() = (System.currentTimeMillis() - creationTime) / 1000
    val idleSeconds: Long get() = (System.currentTimeMillis() - lastActivityTime) / 1000

    override fun toString(): String {
        return "Session($key, sent=$bytesSent, recv=$bytesReceived, app=$appName)"
    }
}

data class SessionSnapshot(
    val key: SessionKey,
    val creationTime: Long,
    val lastActivityTime: Long,
    val bytesSent: Long,
    val bytesReceived: Long,
    val packetsSent: Long,
    val packetsReceived: Long,
    val tcpState: String?,
    val appUid: Int,
    val appName: String,
    val closeReason: String? = null
) {
    val ageSeconds: Long get() = (System.currentTimeMillis() - creationTime) / 1000
    val idleSeconds: Long get() = (System.currentTimeMillis() - lastActivityTime) / 1000
    val totalBytes: Long get() = bytesSent + bytesReceived
}

private class ByteArrayOutputStream {
    private var buffer = ByteArray(8192)
    private var size = 0

    fun write(data: ByteArray) {
        ensureCapacity(size + data.size)
        System.arraycopy(data, 0, buffer, size, data.size)
        size += data.size
    }

    fun toByteArray(): ByteArray {
        val result = ByteArray(size)
        System.arraycopy(buffer, 0, result, 0, size)
        return result
    }

    fun reset() { size = 0 }
    fun size(): Int = size

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        val newSize = maxOf(buffer.size * 2, needed)
        val newBuffer = ByteArray(newSize)
        System.arraycopy(buffer, 0, newBuffer, 0, size)
        buffer = newBuffer
    }
}