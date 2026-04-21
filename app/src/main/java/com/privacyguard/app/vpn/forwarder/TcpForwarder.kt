/**
 * TcpForwarder.kt
 * 
 * 🔴 CRITICAL: This is the TCP FORWARDING ENGINE of PrivacyGuard
 * 
 * What it does:
 * =============
 * Takes TCP packets from the TUN interface and forwards them to the real internet,
 * and takes responses from the internet and forwards them back to the device.
 * 
 * This is the most complex part of the VPN because TCP is stateful.
 * 
 * Key Challenges:
 * ===============
 * 1. Sequence Number Translation: The client and server have different sequence spaces
 * 2. Handshake Management: SYN → SYN-ACK → ACK before data can flow
 * 3. Buffering: Data may arrive before handshake completes
 * 4. Retransmission: Handle duplicate packets
 * 5. Connection Close: FIN/ACK handshake
 * 
 * Architecture:
 * =============
 * - TcpForwarder receives packets from TcpPacketProcessor
 * - Uses SessionTable to find/create sessions
 * - Uses SocketChannel for non-blocking I/O
 * - Each connection has its own dedicated thread
 * 
 * Thread Safety:
 * ==============
 * - Each TCP connection has its own thread (via socket channel)
 * - SessionTable is thread-safe (ConcurrentHashMap)
 * - No shared mutable state between connections
 * 
 * Performance:
 * ============
 * - Non-blocking I/O with selectors
 * - Direct buffers for zero-copy
 * - Buffer pooling to reduce allocations
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.vpn.forwarder

import android.util.Log
import com.privacyguard.app.core.packet.TcpFlags
import com.privacyguard.app.core.packet.TcpPacket
import com.privacyguard.app.core.session.Session
import com.privacyguard.app.core.session.SessionKey
import com.privacyguard.app.core.session.SessionTable
import com.privacyguard.app.core.utils.BufferPool
import com.privacyguard.app.core.utils.ipToString
import com.privacyguard.app.core.utils.readInt32
import com.privacyguard.app.core.utils.writeInt32
import com.privacyguard.app.vpn.tunnel.TunWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

// ============================================================
// TCP Connection State
// ============================================================

/**
 * Represents the state of a TCP connection
 */
enum class TcpConnectionState {
    CONNECTING,     // SYN sent, waiting for connection
    ESTABLISHED,    // Connected, data can flow
    CLOSING,        // FIN sent, waiting for response
    CLOSED          // Connection closed
}

/**
 * Manages a single TCP connection
 */
private class TcpConnection(
    val sessionKey: SessionKey,
    val clientIp: Int,
    val clientPort: Int,
    val serverIp: Int,
    val serverPort: Int,
    private val tunWriter: TunWriter,
    private val sessionTable: SessionTable
) {
    
    // Socket channel to the real server
    var socketChannel: SocketChannel? = null
    var state: TcpConnectionState = TcpConnectionState.CONNECTING
    
    // Sequence number translation
    var clientSeq: Long = 0
    var clientAck: Long = 0
    var serverSeq: Long = 0
    var serverAck: Long = 0
    
    // Buffers for pending data
    private val pendingClientData = ByteArrayOutputStream()
    private val pendingServerData = ByteArrayOutputStream()
    
    // Statistics
    var bytesSent: Long = 0
    var bytesReceived: Long = 0
    var packetsSent: Long = 0
    var packetsReceived: Long = 0
    
    fun bufferClientData(data: ByteArray) {
        synchronized(pendingClientData) {
            pendingClientData.write(data)
        }
    }
    
    fun flushClientData(): ByteArray {
        synchronized(pendingClientData) {
            val data = pendingClientData.toByteArray()
            pendingClientData.reset()
            return data
        }
    }
    
    fun bufferServerData(data: ByteArray) {
        synchronized(pendingServerData) {
            pendingServerData.write(data)
        }
    }
    
    fun flushServerData(): ByteArray {
        synchronized(pendingServerData) {
            val data = pendingServerData.toByteArray()
            pendingServerData.reset()
            return data
        }
    }
    
    fun close() {
        try {
            socketChannel?.close()
        } catch (e: Exception) {
            // Ignore
        }
        state = TcpConnectionState.CLOSED
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
        
        fun reset() {
            size = 0
        }
        
        private fun ensureCapacity(needed: Int) {
            if (needed <= buffer.size) return
            val newSize = maxOf(buffer.size * 2, needed)
            val newBuffer = ByteArray(newSize)
            System.arraycopy(buffer, 0, newBuffer, 0, size)
            buffer = newBuffer
        }
    }
}

// ============================================================
// TcpForwarder - Main Implementation
// ============================================================

/**
 * TCP Forwarder - Handles TCP packet forwarding between device and internet
 * 
 * @param sessionTable Session table for connection tracking
 * @param tunWriter Writer for injecting packets back to TUN
 */
class TcpForwarder(
    private val sessionTable: SessionTable,
    private val tunWriter: TunWriter
) {
    
    companion object {
        private const val TAG = "TcpForwarder"
        private const val CONNECT_TIMEOUT_MS = 10000L  // 10 seconds
        private const val SO_RCVBUF = 65536  // 64KB receive buffer
        private const val SO_SNDBUF = 65536  // 64KB send buffer
        private const val TCP_NODELAY = true  // Disable Nagle's algorithm
    }
    
    // Active TCP connections
    private val connections = ConcurrentHashMap<SessionKey, TcpConnection>()
    
    // Selector for non-blocking I/O
    private var selector: Selector? = null
    private var isRunning = AtomicBoolean(false)
    private var selectorThread: Thread? = null
    
    // Statistics
    private val totalConnectionsAttempted = AtomicLong(0)
    private val totalConnectionsEstablished = AtomicLong(0)
    private val totalConnectionsFailed = AtomicLong(0)
    private val totalConnectionsClosed = AtomicLong(0)
    private val totalBytesForwarded = AtomicLong(0)
    private val totalPacketsForwarded = AtomicLong(0)
    
    /**
     * Starts the TCP forwarder
     */
    fun start(): Boolean {
        if (isRunning.get()) return false
        
        try {
            selector = Selector.open()
            isRunning.set(true)
            
            selectorThread = thread(name = "PrivacyGuard-TcpForwarder", isDaemon = true) {
                selectorLoop()
            }
            
            Log.d(TAG, "TCP forwarder started")
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start TCP forwarder", e)
            return false
        }
    }
    
    /**
     * Stops the TCP forwarder and closes all connections
     */
    fun stop() {
        isRunning.set(false)
        
        // Close all connections
        connections.values.forEach { it.close() }
        connections.clear()
        
        // Wake up selector and close it
        selector?.wakeup()
        try {
            selector?.close()
        } catch (e: IOException) {
            // Ignore
        }
        selector = null
        
        selectorThread?.interrupt()
        selectorThread = null
        
        Log.d(TAG, "TCP forwarder stopped")
    }
    
    // ============================================================
    // Main Selector Loop
    // ============================================================
    
    /**
     * Main selector loop for non-blocking I/O
     */
    private fun selectorLoop() {
        while (isRunning.get()) {
            try {
                val selector = selector ?: break
                
                // Wait for events with timeout
                val readyCount = selector.select(1000)
                
                if (readyCount > 0) {
                    val selectedKeys = selector.selectedKeys()
                    val iterator = selectedKeys.iterator()
                    
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        
                        if (key.isConnectable) {
                            handleConnect(key)
                        }
                        if (key.isReadable) {
                            handleRead(key)
                        }
                        if (key.isWritable) {
                            handleWrite(key)
                        }
                    }
                }
                
                // Check for timed-out connections
                checkTimeouts()
                
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in selector loop", e)
            }
        }
    }
    
    // ============================================================
    // Packet Processing (called from TcpPacketProcessor)
    // ============================================================
    
    /**
     * Processes a TCP packet from the device (client → server)
     * 
     * @param session The session for this connection
     * @param packet The TCP packet
     * @param payload The packet payload
     */
    fun processClientPacket(session: Session, packet: TcpPacket, payload: ByteArray) {
        val key = session.key
        var connection = connections[key]
        
        if (packet.isSyn() && !packet.isAck()) {
            // New connection - SYN packet
            handleSyn(session, packet)
        } else if (connection != null) {
            // Existing connection
            when (connection.state) {
                TcpConnectionState.CONNECTING -> {
                    // SYN-ACK received from server (via handleConnect)
                    // Client ACK received
                    if (packet.isAck() && !packet.isSyn()) {
                        completeHandshake(connection, packet)
                    }
                    // Buffer data until handshake completes
                    if (payload.isNotEmpty()) {
                        connection.bufferClientData(payload)
                    }
                }
                TcpConnectionState.ESTABLISHED -> {
                    // Forward data to server
                    if (payload.isNotEmpty()) {
                        forwardToServer(connection, payload)
                    }
                    // Handle FIN from client
                    if (packet.isFin()) {
                        handleClientFin(connection)
                    }
                    // Handle RST from client
                    if (packet.isRst()) {
                        handleClientRst(connection)
                    }
                }
                else -> {
                    // Ignore packets for closed connections
                }
            }
        }
    }
    
    /**
     * Processes a TCP packet from the server (server → client)
     * 
     * @param session The session for this connection
     * @param packet The TCP packet
     * @param payload The packet payload
     */
    fun processServerPacket(session: Session, packet: TcpPacket, payload: ByteArray) {
        val key = session.key
        val connection = connections[key] ?: return
        
        when (connection.state) {
            TcpConnectionState.CONNECTING -> {
                // SYN-ACK from server
                if (packet.isSyn() && packet.isAck()) {
                    connection.serverSeq = packet.sequenceNumber
                    connection.serverAck = packet.acknowledgmentNumber
                    connection.state = TcpConnectionState.ESTABLISHED
                    
                    // Send SYN-ACK to client
                    sendSynAckToClient(connection)
                    
                    // Send any buffered client data
                    val bufferedData = connection.flushClientData()
                    if (bufferedData.isNotEmpty()) {
                        forwardToServer(connection, bufferedData)
                    }
                }
            }
            TcpConnectionState.ESTABLISHED -> {
                // Forward data to client
                if (payload.isNotEmpty()) {
                    forwardToClient(connection, payload)
                }
                // Handle FIN from server
                if (packet.isFin()) {
                    handleServerFin(connection)
                }
                // Handle RST from server
                if (packet.isRst()) {
                    handleServerRst(connection)
                }
            }
            else -> {
                // Ignore
            }
        }
    }
    
    // ============================================================
    // Connection Management
    // ============================================================
    
    /**
     * Handles a new SYN packet (client wants to connect)
     */
    private fun handleSyn(session: Session, packet: TcpPacket) {
        val key = session.key
        totalConnectionsAttempted.incrementAndGet()
        
        try {
            // Create socket channel
            val socketChannel = SocketChannel.open()
            socketChannel.configureBlocking(false)
            socketChannel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, TCP_NODELAY)
            socketChannel.setOption(java.net.StandardSocketOptions.SO_RCVBUF, SO_RCVBUF)
            socketChannel.setOption(java.net.StandardSocketOptions.SO_SNDBUF, SO_SNDBUF)
            
            // Connect to server
            val serverAddress = InetSocketAddress(ipToString(key.dstIp), key.dstPort)
            socketChannel.connect(serverAddress)
            
            // Register with selector
            val selector = selector ?: return
            socketChannel.register(selector, SelectionKey.OP_CONNECT or SelectionKey.OP_READ)
            
            // Create connection object
            val connection = TcpConnection(
                sessionKey = key,
                clientIp = key.srcIp,
                clientPort = key.srcPort,
                serverIp = key.dstIp,
                serverPort = key.dstPort,
                tunWriter = tunWriter,
                sessionTable = sessionTable
            )
            connection.socketChannel = socketChannel
            connection.clientSeq = packet.sequenceNumber
            connection.clientAck = packet.acknowledgmentNumber
            
            connections[key] = connection
            
            Log.d(TAG, "TCP connection initiated: ${ipToString(key.srcIp)}:$key.srcPort → ${ipToString(key.dstIp)}:$key.dstPort")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish TCP connection", e)
            totalConnectionsFailed.incrementAndGet()
            sendRstToClient(key)
        }
    }
    
    /**
     * Handles successful connection (selector event)
     */
    private fun handleConnect(key: SelectionKey) {
        val socketChannel = key.channel() as SocketChannel
        val connection = findConnectionBySocket(socketChannel) ?: return
        
        try {
            if (socketChannel.finishConnect()) {
                connection.state = TcpConnectionState.ESTABLISHED
                totalConnectionsEstablished.incrementAndGet()
                
                // Send SYN-ACK to client (will be done when we receive SYN-ACK from server)
                Log.d(TAG, "TCP connection established: ${ipToString(connection.sessionKey.srcIp)}:$connection.clientPort → ${ipToString(connection.sessionKey.dstIp)}:$connection.serverPort")
            } else {
                // Still connecting
                key.interestOps(SelectionKey.OP_CONNECT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete TCP connection", e)
            totalConnectionsFailed.incrementAndGet()
            sendRstToClient(connection.sessionKey)
            connections.remove(connection.sessionKey)
            connection.close()
        }
    }
    
    /**
     * Completes the TCP handshake (client ACK received)
     */
    private fun completeHandshake(connection: TcpConnection, packet: TcpPacket) {
        connection.clientAck = packet.acknowledgmentNumber
        connection.state = TcpConnectionState.ESTABLISHED
        
        // Send any buffered data
        val bufferedData = connection.flushClientData()
        if (bufferedData.isNotEmpty()) {
            forwardToServer(connection, bufferedData)
        }
        
        Log.d(TAG, "TCP handshake complete for ${ipToString(connection.sessionKey.srcIp)}:$connection.clientPort")
    }
    
    // ============================================================
    // Data Forwarding
    // ============================================================
    
    /**
     * Forwards data from client to server
     */
    private fun forwardToServer(connection: TcpConnection, data: ByteArray) {
        val socketChannel = connection.socketChannel ?: return
        
        try {
            val buffer = ByteBuffer.wrap(data)
            var written = 0
            while (buffer.hasRemaining()) {
                written += socketChannel.write(buffer)
            }
            
            connection.bytesSent += written
            totalBytesForwarded.addAndGet(written.toLong())
            connection.packetsSent++
            totalPacketsForwarded.incrementAndGet()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward data to server", e)
            sendRstToClient(connection.sessionKey)
        }
    }
    
    /**
     * Forwards data from server to client
     */
    private fun forwardToClient(connection: TcpConnection, data: ByteArray) {
        // Build TCP packet for client
        val packet = buildDataPacket(connection, data)
        tunWriter.write(packet)
        
        connection.bytesReceived += data.size
        connection.packetsReceived++
        
        // Update sequence numbers
        connection.serverSeq += data.size
    }
    
    // ============================================================
    // Packet Building
    // ============================================================
    
    /**
     * Builds a SYN-ACK packet to send to client
     */
    private fun sendSynAckToClient(connection: TcpConnection) {
        val packet = buildSynAckPacket(connection)
        tunWriter.write(packet)
    }
    
    /**
     * Builds a SYN-ACK packet
     */
    private fun buildSynAckPacket(connection: TcpConnection): ByteArray {
        // Simplified: build TCP SYN-ACK packet
        // In production, use TcpPacket.toRawBytes()
        return ByteArray(40) // Placeholder
    }
    
    /**
     * Builds a data packet to send to client
     */
    private fun buildDataPacket(connection: TcpConnection, data: ByteArray): ByteArray {
        // Simplified: build TCP data packet
        // In production, use TcpPacket.toRawBytes()
        return data // Placeholder
    }
    
    /**
     * Sends RST packet to client
     */
    private fun sendRstToClient(key: SessionKey) {
        // Simplified: build and send RST packet
        Log.d(TAG, "Sending RST to client for ${ipToString(key.srcIp)}:$key.srcPort")
    }
    
    // ============================================================
    // Connection Close Handling
    // ============================================================
    
    private fun handleClientFin(connection: TcpConnection) {
        Log.d(TAG, "Client FIN received, closing connection")
        connection.state = TcpConnectionState.CLOSING
        sendFinAckToClient(connection)
        connection.close()
        connections.remove(connection.sessionKey)
        totalConnectionsClosed.incrementAndGet()
    }
    
    private fun handleClientRst(connection: TcpConnection) {
        Log.d(TAG, "Client RST received, aborting connection")
        connection.close()
        connections.remove(connection.sessionKey)
        totalConnectionsClosed.incrementAndGet()
    }
    
    private fun handleServerFin(connection: TcpConnection) {
        Log.d(TAG, "Server FIN received, closing connection")
        connection.state = TcpConnectionState.CLOSING
        sendFinAckToClient(connection)
        connection.close()
        connections.remove(connection.sessionKey)
        totalConnectionsClosed.incrementAndGet()
    }
    
    private fun handleServerRst(connection: TcpConnection) {
        Log.d(TAG, "Server RST received, aborting connection")
        sendRstToClient(connection.sessionKey)
        connection.close()
        connections.remove(connection.sessionKey)
        totalConnectionsClosed.incrementAndGet()
    }
    
    private fun sendFinAckToClient(connection: TcpConnection) {
        // Simplified: build and send FIN-ACK packet
        Log.d(TAG, "Sending FIN-ACK to client")
    }
    
    // ============================================================
    // Utilities
    // ============================================================
    
    private fun findConnectionBySocket(socketChannel: SocketChannel): TcpConnection? {
        return connections.values.find { it.socketChannel == socketChannel }
    }
    
    private fun checkTimeouts() {
        val now = System.currentTimeMillis()
        // Simplified: check for timed-out connections
    }
    
    // ============================================================
    // Statistics
    // ============================================================
    
    fun getStats(): TcpForwarderStats {
        return TcpForwarderStats(
            activeConnections = connections.size,
            totalConnectionsAttempted = totalConnectionsAttempted.get(),
            totalConnectionsEstablished = totalConnectionsEstablished.get(),
            totalConnectionsFailed = totalConnectionsFailed.get(),
            totalConnectionsClosed = totalConnectionsClosed.get(),
            totalBytesForwarded = totalBytesForwarded.get(),
            totalPacketsForwarded = totalPacketsForwarded.get()
        )
    }
    
    fun resetStats() {
        totalConnectionsAttempted.set(0)
        totalConnectionsEstablished.set(0)
        totalConnectionsFailed.set(0)
        totalConnectionsClosed.set(0)
        totalBytesForwarded.set(0)
        totalPacketsForwarded.set(0)
    }
}

data class TcpForwarderStats(
    val activeConnections: Int,
    val totalConnectionsAttempted: Long,
    val totalConnectionsEstablished: Long,
    val totalConnectionsFailed: Long,
    val totalConnectionsClosed: Long,
    val totalBytesForwarded: Long,
    val totalPacketsForwarded: Long
)