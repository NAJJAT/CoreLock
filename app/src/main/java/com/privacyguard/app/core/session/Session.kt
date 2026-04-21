/**
 * Session.kt
 *
 * Represents an active network connection session
 *
 * Each session corresponds to one 5-tuple connection and contains:
 * - The connection key (identifies the connection)
 * - The real socket to the destination
 * - Statistics (bytes sent/received)
 * - Timing information (for cleanup and monitoring)
 * - TCP-specific state (for TCP connections)
 * - App attribution (which app owns this connection)
 *
 * Lifecycle:
 * ==========
 * 1. Created: When first packet (SYN for TCP, first packet for UDP) arrives
 * 2. Active: Packets flowing in both directions
 * 3. Closing: FIN received (TCP only)
 * 4. Closed: Removed from SessionTable, socket closed
 *
 * Memory Considerations:
 * ======================
 * - Each Session holds a Socket (file descriptor)
 * - File descriptors are limited (~1024 per process)
 * - Must close sessions promptly when done
 * - Idle cleanup runs every 30 seconds
 *
 * Thread Safety:
 * ==============
 * - Session data is modified by packet processing threads
 * - Statistics are updated using atomic operations or synchronized blocks
 * - Socket operations are thread-safe (synchronized internally)
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.session

import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * TCP states according to RFC 793
 *
 * Why track TCP state?
 * ====================
 * - SYN_SENT: Waiting for SYN-ACK (handshake not complete)
 * - ESTABLISHED: Data can flow (handshake complete)
 * - FIN_WAIT: Waiting for close confirmation
 *
 * Without state tracking:
 * - May send data before handshake completes (server ignores it)
 * - May not handle FIN correctly (connection hangs)
 * - May not detect RST (connection stays open forever)
 */
enum class TcpState {
    /** No connection (initial state) */
    CLOSED,

    /** Client sent SYN, waiting for SYN-ACK */
    SYN_SENT,

    /** Received SYN-ACK from server, waiting for client ACK */
    SYN_RECEIVED,

    /** ✅ HANDSHAKE COMPLETE - data can flow */
    ESTABLISHED,

    /** Sent FIN, waiting for ACK */
    FIN_WAIT_1,

    /** Received ACK for FIN, waiting for peer FIN */
    FIN_WAIT_2,

    /** Received FIN from peer, sent ACK */
    CLOSE_WAIT,

    /** Sent FIN after CLOSE_WAIT, waiting for ACK */
    LAST_ACK,

    /** Waiting before final close (2 * MSL) */
    TIME_WAIT,

    /** Connection aborted (RST received or sent) */
    RESET;

    /**
     * Returns true if the connection is active (can send/receive data)
     */
    fun isActive(): Boolean = this == ESTABLISHED

    /**
     * Returns true if the connection is closing (FIN sent/received)
     */
    fun isClosing(): Boolean = this in setOf(FIN_WAIT_1, FIN_WAIT_2, CLOSE_WAIT, LAST_ACK)

    /**
     * Returns true if the connection is closed
     */
    fun isClosed(): Boolean = this in setOf(CLOSED, TIME_WAIT, RESET)
}

/**
 * Represents an active connection session
 *
 * This class is mutable - state changes as packets flow.
 * However, the SessionTable ensures thread-safe access.
 *
 * @property key The connection identifier (5-tuple)
 * @property socket The real socket to the destination server
 * @property creationTime Timestamp when session was created (milliseconds since epoch)
 * @property appUid UID of the application that owns this connection (for attribution)
 * @property appName Name of the application (cached for UI)
 */
class Session(
    val key: SessionKey,
    val socket: Socket,
    val creationTime: Long = System.currentTimeMillis(),
    var appUid: Int = -1,
    var appName: String = "Unknown"
) {

    // ============================================================
    // Activity Tracking
    // ============================================================

    /** Last time any packet was sent or received (milliseconds since epoch) */
    @Volatile
    var lastActivityTime: Long = creationTime
        private set

    /** Total bytes sent to the destination */
    private val _bytesSent = AtomicLong(0)
    val bytesSent: Long get() = _bytesSent.get()

    /** Total bytes received from the destination */
    private val _bytesReceived = AtomicLong(0)
    val bytesReceived: Long get() = _bytesReceived.get()

    /** Total packets sent to the destination */
    private val _packetsSent = AtomicLong(0)
    val packetsSent: Long get() = _packetsSent.get()

    /** Total packets received from the destination */
    private val _packetsReceived = AtomicLong(0)
    val packetsReceived: Long get() = _packetsReceived.get()

    // ============================================================
    // TCP-Specific State (null for UDP)
    // ============================================================

    /** Current TCP state (SYN_SENT, ESTABLISHED, etc.) */
    @Volatile
    var tcpState: TcpState? = null

    /** Last TCP sequence number from client (for sequence number translation) */
    @Volatile
    var tcpClientSeq: Long? = null

    /** Last TCP acknowledgment from client */
    @Volatile
    var tcpClientAck: Long? = null

    /** Last TCP sequence number from server (for sequence number translation) */
    @Volatile
    var tcpServerSeq: Long? = null

    /** Last TCP acknowledgment from server */
    @Volatile
    var tcpServerAck: Long? = null

    /** Buffered data waiting for handshake completion (TCP only) */
    private val pendingClientData = ByteArrayOutputStream()
    private val pendingServerData = ByteArrayOutputStream()

    // ============================================================
    // Statistics and Debugging
    // ============================================================

    /** Number of times this session has been retransmitted (TCP only) */
    @Volatile
    var retransmitCount: Int = 0

    /** Round trip time estimate in milliseconds (TCP only) */
    @Volatile
    var estimatedRtt: Int = 100

    /** Error message if session was closed due to error */
    @Volatile
    var closeReason: String? = null

    // ============================================================
    // Public Methods
    // ============================================================

    /**
     * Returns true if this is a TCP session
     */
    fun isTcp(): Boolean = key.isTcp()

    /**
     * Returns true if this is a UDP session
     */
    fun isUdp(): Boolean = key.isUdp()

    /**
     * Updates activity statistics for an outgoing packet (app → server)
     *
     * Called for every outgoing packet (1000+ times per second)
     *
     * @param bytes Number of bytes in the packet (including headers)
     */
    fun recordOutgoing(bytes: Int) {
        lastActivityTime = System.currentTimeMillis()
        _bytesSent.addAndGet(bytes.toLong())
        _packetsSent.incrementAndGet()
    }

    /**
     * Updates activity statistics for an incoming packet (server → app)
     *
     * Called for every incoming packet (1000+ times per second)
     *
     * @param bytes Number of bytes in the packet (including headers)
     */
    fun recordIncoming(bytes: Int) {
        lastActivityTime = System.currentTimeMillis()
        _bytesReceived.addAndGet(bytes.toLong())
        _packetsReceived.incrementAndGet()
    }

    /**
     * Buffers client data while waiting for handshake completion
     *
     * When a TCP connection is in SYN_SENT or SYN_RECEIVED state,
     * the client may already send data. We buffer it until the
     * handshake completes (state becomes ESTABLISHED).
     *
     * @param data The data to buffer
     */
    fun bufferClientData(data: ByteArray) {
        synchronized(pendingClientData) {
            pendingClientData.write(data)
        }
    }

    /**
     * Buffers server data while waiting for handshake completion
     */
    fun bufferServerData(data: ByteArray) {
        synchronized(pendingServerData) {
            pendingServerData.write(data)
        }
    }

    /**
     * Flushes buffered client data to the server
     *
     * Called when handshake completes (state becomes ESTABLISHED)
     *
     * @return The buffered data, or empty array if none
     */
    fun flushClientBuffer(): ByteArray {
        synchronized(pendingClientData) {
            val data = pendingClientData.toByteArray()
            pendingClientData.reset()
            return data
        }
    }

    /**
     * Flushes buffered server data to the client
     */
    fun flushServerBuffer(): ByteArray {
        synchronized(pendingServerData) {
            val data = pendingServerData.toByteArray()
            pendingServerData.reset()
            return data
        }
    }

    /**
     * Returns true if this session has buffered client data
     */
    fun hasBufferedClientData(): Boolean {
        synchronized(pendingClientData) {
            return pendingClientData.size() > 0
        }
    }

    /**
     * Returns true if this session has buffered server data
     */
    fun hasBufferedServerData(): Boolean {
        synchronized(pendingServerData) {
            return pendingServerData.size() > 0
        }
    }

    /**
     * Returns true if this session is idle and should be cleaned up
     *
     * Why different timeouts for TCP and UDP?
     * =======================================
     * - TCP has keep-alive (usually 30-60 seconds)
     * - UDP is connectionless (no keep-alive)
     * - UDP sessions are cleaned up more aggressively
     *
     * @param tcpTimeoutMillis Timeout for TCP connections (default: 30 seconds)
     * @param udpTimeoutMillis Timeout for UDP connections (default: 10 seconds)
     */
    fun isIdle(tcpTimeoutMillis: Long = 30_000, udpTimeoutMillis: Long = 10_000): Boolean {
        val timeout = if (isTcp()) tcpTimeoutMillis else udpTimeoutMillis
        return System.currentTimeMillis() - lastActivityTime > timeout
    }

    /**
     * Returns true if this session is older than the specified age
     * Used for statistics and debugging
     */
    fun isOlderThan(millis: Long): Boolean {
        return System.currentTimeMillis() - creationTime > millis
    }

    /**
     * Calculates the current throughput in bytes per second
     *
     * Uses a simple moving average over the last 10 seconds.
     *
     * @return Throughput in bytes per second (0 if no activity)
     */
    fun getCurrentThroughput(): Long {
        val age = System.currentTimeMillis() - creationTime
        if (age < 1000) return 0
        val totalBytes = bytesSent + bytesReceived
        return totalBytes * 1000 / age
    }

    /**
     * Closes the socket and releases resources
     *
     * This is called when:
     * - Connection is finished (FIN received from both sides)
     * - Connection is reset (RST received)
     * - Session is idle for too long
     * - VPN stops
     *
     * Thread-safe: Can be called multiple times
     *
     * @param reason Why the session is being closed (for debugging)
     */
    fun close(reason: String = "normal") {
        closeReason = reason
        try {
            if (!socket.isClosed) {
                socket.close()
            }
        } catch (e: Exception) {
            // Socket may already be closed or invalid
            // Log but don't crash
            android.util.Log.w("Session", "Error closing socket for ${key}: ${e.message}")
        }

        // Clear buffers
        synchronized(pendingClientData) {
            pendingClientData.reset()
        }
        synchronized(pendingServerData) {
            pendingServerData.reset()
        }
    }

    /**
     * Returns a snapshot of session data for UI display
     *
     * This creates a lightweight copy without the socket.
     * Safe to send to UI thread.
     */
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

    /**
     * Returns a summary of the session for logging
     */
    fun summary(): String {
        return "Session(${key}, sent=$bytesSent, recv=$bytesReceived, " +
                "state=${tcpState?.name ?: "UDP"}, app=$appName, age=${ageSeconds}s)"
    }

    /**
     * Age of the session in seconds
     */
    val ageSeconds: Long
        get() = (System.currentTimeMillis() - creationTime) / 1000

    /**
     * Idle time in seconds
     */
    val idleSeconds: Long
        get() = (System.currentTimeMillis() - lastActivityTime) / 1000

    override fun toString(): String = summary()
}

/**
 * Lightweight snapshot of session data for UI
 *
 * This doesn't contain the socket (can't be serialized)
 * Safe to send to UI thread via StateFlow.
 */
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
    val totalPackets: Long get() = packetsSent + packetsReceived

    /**
     * Returns a formatted string for UI display
     */
    fun format(): String {
        return "${key} | ${appName} | ${formatBytes(totalBytes)} | ${tcpState ?: "UDP"}"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}

/**
 * ByteArrayOutputStream with size access
 * Used for buffering data during TCP handshake
 */
private class ByteArrayOutputStream {
    private var buffer = ByteArray(8192)  // Start with 8KB
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

    fun reset() {
        size = 0
    }

    fun size(): Int = size

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        val newSize = maxOf(buffer.size * 2, needed)
        val newBuffer = ByteArray(newSize)
        System.arraycopy(buffer, 0, newBuffer, 0, size)
        buffer = newBuffer
    }
}