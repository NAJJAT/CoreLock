/**
 * SessionTable.kt
 *
 * 🔴 CRITICAL: This is the MOST IMPORTANT class in PrivacyGuard
 *
 * Maps every fake connection from the TUN interface to a real socket connection.
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.session

import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SessionTable {

    private val forwardMap = ConcurrentHashMap<SessionKey, Session>()
    private val reverseMap = ConcurrentHashMap<SessionKey, SessionKey>()

    private val totalSessionsCreated = AtomicLong(0)
    private val totalSessionsClosed = AtomicLong(0)
    private val totalBytesForwarded = AtomicLong(0)
    private val totalPacketsForwarded = AtomicLong(0)

    fun register(key: SessionKey, socket: Socket, appUid: Int = -1, appName: String = "Unknown"): Session {
        val existing = forwardMap[key]
        if (existing != null) {
            try { socket.close() } catch (e: Exception) { }
            return existing
        }

        val session = Session(key, socket, appUid = appUid, appName = appName)
        forwardMap[key] = session
        reverseMap[key.reverse()] = key
        totalSessionsCreated.incrementAndGet()
        return session
    }

    fun getOutgoing(key: SessionKey): Session? = forwardMap[key]

    fun getIncoming(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, protocol: Int): Session? {
        val reverseKey = SessionKey(srcIp, srcPort, dstIp, dstPort, protocol)
        val originalKey = reverseMap[reverseKey]
        return originalKey?.let { forwardMap[it] }
    }

    operator fun get(key: SessionKey): Session? = forwardMap[key]

    fun remove(key: SessionKey): Session? {
        val session = forwardMap.remove(key)
        session?.close()
        reverseMap.remove(key.reverse())
        if (session != null) {
            totalSessionsClosed.incrementAndGet()
            totalBytesForwarded.addAndGet(session.bytesSent + session.bytesReceived)
            totalPacketsForwarded.addAndGet((session.packetsSent + session.packetsReceived))
        }
        return session
    }

    fun removeByReverse(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, protocol: Int): Session? {
        val reverseKey = SessionKey(srcIp, srcPort, dstIp, dstPort, protocol)
        val originalKey = reverseMap.remove(reverseKey)
        return originalKey?.let { remove(it) }
    }

    fun clear() {
        forwardMap.values.forEach { it.close() }
        forwardMap.clear()
        reverseMap.clear()
    }

    fun cleanupIdle(timeoutMillis: Long = 30000): Int {
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

    fun size(): Int = forwardMap.size
    fun isEmpty(): Boolean = forwardMap.isEmpty()

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

    fun snapshot(): List<SessionSnapshot> {
        return forwardMap.values.map { it.snapshot() }
    }

    fun getSessionsForApp(uid: Int): List<SessionSnapshot> {
        return forwardMap.values.filter { it.appUid == uid }.map { it.snapshot() }
    }

    fun updateTcpState(key: SessionKey, state: TcpState) {
        forwardMap[key]?.tcpState = state
    }

    fun updateTcpSequences(key: SessionKey, clientSeq: Long? = null, clientAck: Long? = null, serverSeq: Long? = null, serverAck: Long? = null) {
        val session = forwardMap[key] ?: return
        clientSeq?.let { session.tcpClientSeq = it }
        clientAck?.let { session.tcpClientAck = it }
        serverSeq?.let { session.tcpServerSeq = it }
        serverAck?.let { session.tcpServerAck = it }
    }
}

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
}