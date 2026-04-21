/**
 * UdpForwarder.kt
 * 
 * UDP packet forwarding engine
 * 
 * What it does:
 * =============
 * Forwards UDP packets between device and internet.
 * UDP is connectionless - simpler than TCP.
 * 
 * Key challenges:
 * ===============
 * 1. Session tracking (UDP has no handshake)
 * 2. Timeout management (UDP sessions expire after inactivity)
 * 3. DNS interception (special handling for port 53)
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.vpn.forwarder

import android.util.Log
import com.privacyguard.app.core.session.SessionKey
import com.privacyguard.app.core.session.SessionTable
import com.privacyguard.app.core.utils.ipToString
import com.privacyguard.app.vpn.tunnel.TunWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * UDP Forwarder - Handles UDP packet forwarding
 */
class UdpForwarder(
    private val sessionTable: SessionTable,
    private val tunWriter: TunWriter
) {
    
    companion object {
        private const val TAG = "UdpForwarder"
        private const val SESSION_TIMEOUT_MS = 30000L  // 30 seconds
        private const val CLEANUP_INTERVAL_MS = 10000L  // 10 seconds
    }
    
    // Active UDP sessions
    private data class UdpSession(
        val key: SessionKey,
        val socket: DatagramSocket,
        var lastActivityTime: Long = System.currentTimeMillis(),
        var bytesSent: Long = 0,
        var bytesReceived: Long = 0,
        var packetsSent: Long = 0,
        var packetsReceived: Long = 0
    )
    
    private val sessions = ConcurrentHashMap<SessionKey, UdpSession>()
    private val reverseMap = ConcurrentHashMap<SessionKey, SessionKey>()
    
    private var isRunning = AtomicBoolean(false)
    private var cleanupThread: Thread? = null
    
    // Statistics
    private val totalSessionsCreated = AtomicLong(0)
    private val totalSessionsClosed = AtomicLong(0)
    private val totalBytesForwarded = AtomicLong(0)
    private val totalPacketsForwarded = AtomicLong(0)
    
    /**
     * Starts the UDP forwarder
     */
    fun start(): Boolean {
        if (isRunning.get()) return false
        
        isRunning.set(true)
        
        cleanupThread = thread(name = "PrivacyGuard-UdpCleanup", isDaemon = true) {
            cleanupLoop()
        }
        
        Log.d(TAG, "UDP forwarder started")
        return true
    }
    
    /**
     * Stops the UDP forwarder
     */
    fun stop() {
        isRunning.set(false)
        
        // Close all sessions
        sessions.values.forEach { it.socket.close() }
        sessions.clear()
        reverseMap.clear()
        
        cleanupThread?.interrupt()
        cleanupThread = null
        
        Log.d(TAG, "UDP forwarder stopped")
    }
    
    /**
     * Processes a UDP packet from device (client → server)
     */
    fun processClientPacket(key: SessionKey, data: ByteArray, length: Int) {
        var session = sessions[key]
        
        if (session == null) {
            // Create new UDP session
            session = createSession(key)
            sessions[key] = session
            totalSessionsCreated.incrementAndGet()
            
            // Store reverse mapping
            val reverseKey = SessionKey(
                srcIp = key.dstIp,
                srcPort = key.dstPort,
                dstIp = key.srcIp,
                dstPort = key.srcPort,
                protocol = key.protocol
            )
            reverseMap[reverseKey] = key
        }
        
        // Forward to server
        forwardToServer(session, data, length)
    }
    
    /**
     * Processes a UDP packet from server (server → client)
     */
    fun processServerPacket(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, data: ByteArray, length: Int) {
        val reverseKey = SessionKey(srcIp, srcPort, dstIp, dstPort, 17)
        val originalKey = reverseMap[reverseKey]
        
        if (originalKey != null) {
            val session = sessions[originalKey]
            if (session != null) {
                forwardToClient(session, data, length)
            }
        }
    }
    
    /**
     * Creates a new UDP session
     */
    private fun createSession(key: SessionKey): UdpSession {
        val socket = DatagramSocket()
        socket.soTimeout = 0  // Blocking mode
        socket.connect(InetSocketAddress(ipToString(key.dstIp), key.dstPort))
        
        return UdpSession(key, socket)
    }
    
    /**
     * Forwards data from client to server
     */
    private fun forwardToServer(session: UdpSession, data: ByteArray, length: Int) {
        try {
            val packet = DatagramPacket(data, length)
            session.socket.send(packet)
            
            session.lastActivityTime = System.currentTimeMillis()
            session.bytesSent += length
            session.packetsSent++
            totalBytesForwarded.addAndGet(length.toLong())
            totalPacketsForwarded.incrementAndGet()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward UDP to server", e)
        }
    }
    
    /**
     * Forwards data from server to client
     */
    private fun forwardToClient(session: UdpSession, data: ByteArray, length: Int) {
        // Build IP packet for client and write to TUN
        // Simplified: in production, build proper IP/UDP packet
        tunWriter.write(data)
        
        session.lastActivityTime = System.currentTimeMillis()
        session.bytesReceived += length
        session.packetsReceived++
    }
    
    /**
     * Cleanup loop for idle sessions
     */
    private fun cleanupLoop() {
        while (isRunning.get()) {
            try {
                Thread.sleep(CLEANUP_INTERVAL_MS)
                
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<SessionKey>()
                
                for ((key, session) in sessions) {
                    if (now - session.lastActivityTime > SESSION_TIMEOUT_MS) {
                        toRemove.add(key)
                    }
                }
                
                for (key in toRemove) {
                    val session = sessions.remove(key)
                    session?.socket?.close()
                    totalSessionsClosed.incrementAndGet()
                    
                    // Remove reverse mapping
                    val reverseKey = SessionKey(
                        srcIp = key.dstIp,
                        srcPort = key.dstPort,
                        dstIp = key.srcIp,
                        dstPort = key.srcPort,
                        protocol = key.protocol
                    )
                    reverseMap.remove(reverseKey)
                }
                
                if (toRemove.isNotEmpty()) {
                    Log.d(TAG, "Cleaned up ${toRemove.size} idle UDP sessions")
                }
                
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in cleanup loop", e)
            }
        }
    }
    
    /**
     * Returns forwarder statistics
     */
    fun getStats(): UdpForwarderStats {
        return UdpForwarderStats(
            activeSessions = sessions.size,
            totalSessionsCreated = totalSessionsCreated.get(),
            totalSessionsClosed = totalSessionsClosed.get(),
            totalBytesForwarded = totalBytesForwarded.get(),
            totalPacketsForwarded = totalPacketsForwarded.get()
        )
    }
    
    fun resetStats() {
        totalSessionsCreated.set(0)
        totalSessionsClosed.set(0)
        totalBytesForwarded.set(0)
        totalPacketsForwarded.set(0)
    }
}

data class UdpForwarderStats(
    val activeSessions: Int,
    val totalSessionsCreated: Long,
    val totalSessionsClosed: Long,
    val totalBytesForwarded: Long,
    val totalPacketsForwarded: Long
)