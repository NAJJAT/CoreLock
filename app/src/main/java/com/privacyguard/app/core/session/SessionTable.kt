/**
 * SessionTable.kt
 *
 * 🔴 CRITICAL: This is the MOST IMPORTANT class in PrivacyGuard
 *
 * What it does:
 * =============
 * Maps every fake connection from the TUN interface to a real socket connection.
 * Without this, packets from the internet cannot find their way back to the app.
 *
 * Why this is the heart of the VPN:
 * ==================================
 * When an app (Chrome) sends a packet:
 *   - TUN gives it a fake source IP: 10.0.0.2:12345
 *   - We open a REAL socket to the destination (8.8.8.8:443)
 *   - SessionTable stores: (10.0.0.2:12345 → 8.8.8.8:443) = socket
 *
 * When the response comes back from 8.8.8.8:443:
 *   - SessionTable looks up: (8.8.8.8:443 → 10.0.0.2:12345)
 *   - Knows which app (Chrome) sent the original request
 *   - Injects the response back into TUN for Chrome to receive
 *
 * Without this mapping, responses would be dropped and connections would timeout.
 *
 * Thread Safety:
 * ==============
 * This table is accessed by:
 *   - TunReader thread (reading packets from TUN)
 *   - TcpForwarder threads (handling socket I/O)
 *   - UdpForwarder threads (handling UDP)
 *   - UI thread (reading statistics)
 *
 * All operations must be thread-safe. ConcurrentHashMap provides this.
 *
 * Performance Requirements:
 * =========================
 * - Lookup must be O(1) for every packet (10,000+ packets/sec)
 * - Cleanup must not block lookups
 * - Memory must scale to 10,000+ active connections
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.session

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Represents a unique network connection (5-tuple).
 *
 * The 5-tuple uniquely identifies a TCP/UDP connection:
 * - Source IP (who is sending)
 * - Source Port (which application port)
 * - Destination IP (where it's going)
 * - Destination Port (which service on the destination)
 * - Protocol (6 = TCP, 17 = UDP)
 *
 * Why use Int for IPs instead of String?
 * ========================================
 * - IP as Int: 4 bytes, comparison is O(1), HashMap works faster
 * - IP as String: "192.168.1.1" is 15 bytes, comparison is O(n)
 * - For 10,000 active connections, Int is 100x faster and uses 75% less memory
 *
 * @property srcIp Source IP address (network byte order, as Int)
 * @property srcPort Source port (1-65535)
 * @property dstIp Destination IP address (network byte order, as Int)
 * @property dstPort Destination port (1-65535)
 * @property protocol Protocol (6=TCP, 17=UDP)
 */
data class ConnectionKey(
    val srcIp: Int,
    val srcPort: Int,
    val dstIp: Int,
    val dstPort: Int,
    val protocol: Int
) {

    /**
     * Returns true if this is a TCP connection
     */
    fun isTcp(): Boolean = protocol == 6

    /**
     * Returns true if this is a UDP connection
     */
    fun isUdp(): Boolean = protocol == 17

    /**
     * Creates the reverse key for response lookup
     *
     * When a response comes from the server, source and destination are swapped.
     * This method creates the key we use to look up the original session.
     */
    fun reverse(): ConnectionKey {
        return ConnectionKey(
            srcIp = dstIp,
            srcPort = dstPort,
            dstIp = srcIp,
            dstPort = srcPort,
            protocol = protocol
        )
    }

    /**
     * Human-readable representation for debugging
     *
     * WARNING: This is SLOW. Only use for logging, never for packet processing.
     */
    override fun toString(): String {
        return "${ipToString(srcIp)}:$srcPort → ${ipToString(dstIp)}:$dstPort (${protocolName()})"
    }

    private fun ipToString(ip: Int): String {
        return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
    }

    private fun protocolName(): String = when (protocol) {
        6 -> "TCP"
        17 -> "UDP"
        else -> "Unknown($protocol)"
    }

    companion object {
        /**
         * Creates a ConnectionKey from raw packet headers
         *
         * @param srcIpBytes Source IP as 4-byte array
         * @param srcPort Source port
         * @param dstIpBytes Destination IP as 4-byte array
         * @param dstPort Destination port
         * @param protocol Protocol number (6=TCP, 17=UDP)
         */
        fun fromBytes(
            srcIpBytes: ByteArray,
            srcPort: Int,
            dstIpBytes: ByteArray,
            dstPort: Int,
            protocol: Int
        ): ConnectionKey {
            val srcIp = ((srcIpBytes[0].toInt() and 0xFF) shl 24) or
                    ((srcIpBytes[1].toInt() and 0xFF) shl 16) or
                    ((srcIpBytes[2].toInt() and 0xFF) shl 8) or
                    (srcIpBytes[3].toInt() and 0xFF)

            val dstIp = ((dstIpBytes[0].toInt() and 0xFF) shl 24) or
                    ((dstIpBytes[1].toInt() and 0xFF) shl 16) or
                    ((dstIpBytes[2].toInt() and 0xFF) shl 8) or
                    (dstIpBytes[3].toInt() and 0xFF)

            return ConnectionKey(srcIp, srcPort, dstIp, dstPort, protocol)
        }
    }
}

/**
 * Represents an active connection session
 *
 * Each session contains:
 * - The connection key (5-tuple)
 * - The real socket to the destination
 * - Statistics (bytes sent/received)
 * - Timing information (for cleanup)
 * - TCP-specific state (for TCP connections)
 *
 * @property key The connection identifier
 * @property socket The real socket to the destination server
 * @property creationTime Timestamp when session was created (milliseconds since epoch)
 * @property lastActivityTime Timestamp of last packet (updated on every packet)
 * @property bytesSent Total bytes sent to the destination
 * @property bytesReceived Total bytes received from the destination
 * @property packetsSent Total packets sent to the destination
 * @property packetsReceived Total packets received from the destination
 * @property tcpClientSeq Last TCP sequence number from client (TCP only)
 * @property tcpClientAck Last TCP acknowledgment from client (TCP only)
 * @property tcpServerSeq Last TCP sequence number from server (TCP only)
 * @property tcpServerAck Last TCP acknowledgment from server (TCP only)
 * @property tcpState Current TCP state (TCP only)
 * @property appUid UID of the application that owns this connection (for attribution)
 * @property appName Name of the application (cached for UI)
 */
class Session(
    val key: ConnectionKey,
    val socket: Socket,
    val creationTime: Long = System.currentTimeMillis(),
    var lastActivityTime: Long = creationTime,
    var bytesSent: Long = 0,
    var bytesReceived: Long = 0,
    var packetsSent: Int = 0,
    var packetsReceived: Int = 0,
    // TCP-specific fields (null for UDP)
    var tcpClientSeq: Long? = null,
    var tcpClientAck: Long? = null,
    var tcpServerSeq: Long? = null,
    var tcpServerAck: Long? = null,
    var tcpState: TcpState? = null,
    // App attribution
    var appUid: Int = -1,
    var appName: String = "Unknown"
) {

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
     */
    fun recordOutgoing(bytes: Int) {
        lastActivityTime = System.currentTimeMillis()
        bytesSent += bytes
        packetsSent++
    }

    /**
     * Updates activity statistics for an incoming packet (server → app)
     */
    fun recordIncoming(bytes: Int) {
        lastActivityTime = System.currentTimeMillis()
        bytesReceived += bytes
        packetsReceived++
    }

    /**
     * Returns true if this session is idle and should be cleaned up
     *
     * Why 30 seconds?
     * ===============
     * TCP keep-alive is typically 30-60 seconds.
     * UDP has no keep-alive, so we use a shorter timeout.
     *
     * @param timeoutMillis Timeout in milliseconds (default: 30 seconds for TCP, 10 for UDP)
     */
    fun isIdle(timeoutMillis: Long = if (isTcp()) 30_000 else 10_000): Boolean {
        return System.currentTimeMillis() - lastActivityTime > timeoutMillis
    }

    /**
     * Returns true if this session is older than the specified age
     * Used for statistics and debugging
     */
    fun isOlderThan(millis: Long): Boolean {
        return System.currentTimeMillis() - creationTime > millis
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
     */
    fun close() {
        try {
            if (!socket.isClosed) {
                socket.close()
            }
        } catch (e: Exception) {
            // Socket may already be closed or invalid
            // Log but don't crash
            android.util.Log.w("Session", "Error closing socket for ${key}: ${e.message}")
        }
    }

    /**
     * Returns a snapshot of session data for UI display
     * This is a lightweight copy without the socket
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
            appName = appName
        )
    }

    override fun toString(): String {
        return "Session($key, sent=$bytesSent, recv=$bytesReceived, app=$appName)"
    }
}

/**
 * TCP states according to RFC 793
 *
 * Why track TCP state?
 * ====================
 * Without state, we don't know if a connection is ready for data.
 * Example: Chrome sends data before handshake completes.
 * We need to buffer that data until state = ESTABLISHED.
 */
enum class TcpState {
    CLOSED,          // No connection (initial state)
    SYN_SENT,        // Client sent SYN, waiting for SYN-ACK
    SYN_RECEIVED,    // Received SYN-ACK from server, waiting for client ACK
    ESTABLISHED,     // ✅ HANDSHAKE COMPLETE - data can flow
    FIN_WAIT_1,      // Sent FIN, waiting for ACK
    FIN_WAIT_2,      // Received ACK for FIN, waiting for peer FIN
    CLOSE_WAIT,      // Received FIN from peer, sent ACK
    LAST_ACK,        // Sent FIN after CLOSE_WAIT, waiting for ACK
    TIME_WAIT,       // Waiting before final close (2 * MSL)
    RESET            // Connection aborted (RST received or sent)
}

/**
 * Lightweight snapshot of session data for UI
 *
 * This doesn't contain the socket (can't be serialized)
 * Safe to send to UI thread
 */
data class SessionSnapshot(
    val key: ConnectionKey,
    val creationTime: Long,
    val lastActivityTime: Long,
    val bytesSent: Long,
    val bytesReceived: Long,
    val packetsSent: Int,
    val packetsReceived: Int,
    val tcpState: String?,
    val appUid: Int,
    val appName: String
) {
    val ageSeconds: Long get() = (System.currentTimeMillis() - creationTime) / 1000
    val idleSeconds: Long get() = (System.currentTimeMillis() - lastActivityTime) / 1000
}

/**
 * SessionTable - The heart of the VPN
 *
 * This class manages all active connections.
 *
 * Thread Safety:
 * ==============
 * - ConcurrentHashMap for O(1) thread-safe lookups
 * - forwardMap: (client → server) lookup when app sends packet
 * - reverseMap: (server → client) lookup when response comes back
 *
 * Why two maps?
 * =============
 * Without reverseMap, we would have to search through all sessions to find the client.
 * For 10,000 sessions, that's O(n) per packet → 10,000 operations → SLOW.
 * With reverseMap, it's O(1) → FAST.
 *
 * Memory overhead: 2 maps instead of 1 = ~2x memory, but worth it for performance.
 *
 * Statistics:
 * ===========
 * - totalSessionsCreated: Counter for debugging
 * - totalBytesForwarded: Bandwidth tracking
 * - totalPacketsForwarded: Packet rate tracking
 */
class SessionTable {

    // Primary map: Fake connection (from TUN) → Session
    private val forwardMap = ConcurrentHashMap<ConnectionKey, Session>()

    // Reverse map: Real server connection → ConnectionKey
    // Key: (server IP, server port, protocol) without client info
    private val reverseMap = ConcurrentHashMap<ConnectionKey, ConnectionKey>()

    // Statistics (atomic for thread safety)
    private val totalSessionsCreated = AtomicLong(0)
    private val totalSessionsClosed = AtomicLong(0)
    private val totalBytesForwarded = AtomicLong(0)
    private val totalPacketsForwarded = AtomicLong(0)

    /**
     * Registers a new connection
     *
     * When to call:
     * - TCP SYN packet received (new connection)
     * - UDP first packet received (new connection)
     *
     * Thread-safe: ConcurrentHashMap handles concurrent registration
     *
     * @param key The connection key (from the TUN packet)
     * @param socket The real socket connected to the destination
     * @param appUid UID of the application (for attribution)
     * @param appName Name of the application
     * @return The created or existing Session
     */
    fun register(
        key: ConnectionKey,
        socket: Socket,
        appUid: Int = -1,
        appName: String = "Unknown"
    ): Session {
        // Check if session already exists (race condition protection)
        val existing = forwardMap[key]
        if (existing != null) {
            // Session already exists, close the new socket and return existing
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
            return existing
        }

        // Create new session
        val session = Session(
            key = key,
            socket = socket,
            appUid = appUid,
            appName = appName
        )

        // Store in forward map
        forwardMap[key] = session

        // Create reverse key for lookups
        val reverseKey = key.reverse()
        reverseMap[reverseKey] = key

        // Update statistics
        totalSessionsCreated.incrementAndGet()

        return session
    }

    /**
     * Finds a session for an outgoing packet (client → server)
     *
     * Performance: O(1) average
     * Called for every outgoing packet (~1000 times per second)
     *
     * @param key The connection key from the packet
     * @return Session or null if not found
     */
    fun getOutgoing(key: ConnectionKey): Session? {
        return forwardMap[key]
    }

    /**
     * Finds a session for an incoming packet (server → client)
     *
     * Performance: O(1) average
     * Called for every incoming packet (~1000 times per second)
     *
     * Why this is critical:
     * =====================
     * Without this, we wouldn't know which app the response belongs to.
     * The response would be dropped, and the app would timeout.
     *
     * @param srcIp Source IP (from the incoming packet - this is the server)
     * @param srcPort Source port (server port)
     * @param dstIp Destination IP (client IP from TUN)
     * @param dstPort Destination port (client port)
     * @param protocol Protocol (6=TCP, 17=UDP)
     * @return Session or null if not found
     */
    fun getIncoming(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, protocol: Int): Session? {
        val reverseKey = ConnectionKey(srcIp, srcPort, dstIp, dstPort, protocol)
        val originalKey = reverseMap[reverseKey]
        return originalKey?.let { forwardMap[it] }
    }

    /**
     * Gets a session by its key (forward lookup)
     */
    operator fun get(key: ConnectionKey): Session? = forwardMap[key]

    /**
     * Removes a session and closes its socket
     *
     * When to call:
     * - TCP FIN received from both sides
     * - TCP RST received
     * - Session idle timeout
     * - VPN stops
     *
     * Thread-safe: ConcurrentHashMap handles concurrent removal
     *
     * @param key The connection key
     * @return The removed Session, or null if not found
     */
    fun remove(key: ConnectionKey): Session? {
        val session = forwardMap.remove(key)
        session?.close()

        // Also remove from reverse map
        val reverseKey = key.reverse()
        reverseMap.remove(reverseKey)

        if (session != null) {
            totalSessionsClosed.incrementAndGet()
            totalBytesForwarded.addAndGet(session.bytesSent + session.bytesReceived)
            totalPacketsForwarded.addAndGet((session.packetsSent + session.packetsReceived).toLong())
        }

        return session
    }

    /**
     * Removes a session by its reverse key
     * Used when we only have the reverse key
     */
    fun removeByReverse(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, protocol: Int): Session? {
        val reverseKey = ConnectionKey(srcIp, srcPort, dstIp, dstPort, protocol)
        val originalKey = reverseMap.remove(reverseKey)
        return originalKey?.let { remove(it) }
    }

    /**
     * Removes all sessions (called when VPN stops)
     */
    fun clear() {
        forwardMap.values.forEach { it.close() }
        forwardMap.clear()
        reverseMap.clear()
    }

    /**
     * Removes idle sessions to free resources
     *
     * When to call:
     * - Every 30 seconds (via WorkManager or Coroutine)
     * - When memory pressure is high
     *
     * @param timeoutMillis Timeout for idle sessions (default: 30 seconds for TCP, 10 for UDP)
     * @return Number of sessions removed
     */
    fun cleanupIdle(timeoutMillis: Long = 30_000): Int {
        var removed = 0
        val iterator = forwardMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val session = entry.value
            val sessionTimeout = if (session.isTcp()) timeoutMillis else timeoutMillis / 3
            if (session.isIdle(sessionTimeout)) {
                session.close()
                iterator.remove()
                reverseMap.remove(session.key.reverse())
                removed++
                totalSessionsClosed.incrementAndGet()
            }
        }
        return removed
    }

    /**
     * Returns the number of active sessions
     */
    fun size(): Int = forwardMap.size

    /**
     * Returns true if the table is empty
     */
    fun isEmpty(): Boolean = forwardMap.isEmpty()

    /**
     * Returns statistics about the session table
     */
    fun getStats(): SessionTableStats {
        return SessionTableStats(
            activeSessions = size(),
            totalSessionsCreated = totalSessionsCreated.get(),
            totalSessionsClosed = totalSessionsClosed.get(),
            totalBytesForwarded = totalBytesForwarded.get(),
            totalPacketsForwarded = totalPacketsForwarded.get(),
            tcpSessions = forwardMap.values.count { it.isTcp() },
            udpSessions = forwardMap.values.count { it.isUdp() }
        )
    }

    /**
     * Returns a snapshot of all sessions for UI display
     *
     * Why snapshot instead of direct access?
     * =======================================
     * Direct access would require locking the entire table.
     * Snapshot copies only the data we need (not the sockets).
     * UI can display the snapshot without blocking packet processing.
     */
    fun snapshot(): List<SessionSnapshot> {
        return forwardMap.values.map { it.snapshot() }
    }

    /**
     * Returns sessions for a specific app (by UID)
     */
    fun getSessionsForApp(uid: Int): List<SessionSnapshot> {
        return forwardMap.values
            .filter { it.appUid == uid }
            .map { it.snapshot() }
    }

    /**
     * Updates TCP state for a session
     */
    fun updateTcpState(key: ConnectionKey, state: TcpState) {
        forwardMap[key]?.tcpState = state
    }

    /**
     * Updates TCP sequence numbers for a session
     */
    fun updateTcpSequences(
        key: ConnectionKey,
        clientSeq: Long? = null,
        clientAck: Long? = null,
        serverSeq: Long? = null,
        serverAck: Long? = null
    ) {
        val session = forwardMap[key] ?: return
        clientSeq?.let { session.tcpClientSeq = it }
        clientAck?.let { session.tcpClientAck = it }
        serverSeq?.let { session.tcpServerSeq = it }
        serverAck?.let { session.tcpServerAck = it }
    }
}

/**
 * Statistics about the session table
 */
data class SessionTableStats(
    val activeSessions: Int,
    val totalSessionsCreated: Long,
    val totalSessionsClosed: Long,
    val totalBytesForwarded: Long,
    val totalPacketsForwarded: Long,
    val tcpSessions: Int,
    val udpSessions: Int
) {
    val totalBytesMB: Double get() = totalBytesForwarded / (1024.0 * 1024.0)
    val averagePacketsPerSession: Double get() = if (totalSessionsCreated > 0) {
        totalPacketsForwarded.toDouble() / totalSessionsCreated
    } else 0.0
}