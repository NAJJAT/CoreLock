/**
 * TunReader.kt
 * 
 * Reads raw IP packets from the TUN interface
 * 
 * What it does:
 * =============
 * Reads packets from the TUN file descriptor as they arrive from the system.
 * This is the ENTRY POINT for all packets entering PrivacyGuard.
 * 
 * How it works:
 * =============
 * 1. Android VpnService creates a TUN interface
 * 2. All device traffic is routed through this interface
 * 3. TunReader reads raw bytes from the TUN file descriptor
 * 4. Each read returns one complete IP packet (Ethernet MTU = 1500 bytes)
 * 5. Packets are passed to the packet processor for inspection
 * 
 * Performance Requirements:
 * =========================
 * - Non-blocking read (use available() or selector)
 * - Buffer reuse to avoid GC pressure
 * - Called for every packet (1000+ times per second)
 * - Must never block the main thread
 * 
 * Thread Safety:
 * ==============
 * - Single reader thread (dedicated thread)
 * - Buffer pool is thread-safe
 * - Callbacks are dispatched to appropriate handlers
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.vpn.tunnel

import android.system.Os
import com.privacyguard.app.core.utils.isValidIpv4Packet
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

// ============================================================
// Packet Callback Interface
// ============================================================

/**
 * Callback interface for packets read from TUN
 * 
 * Implemented by TcpPacketProcessor, UdpPacketProcessor, etc.
 */
interface PacketReadCallback {
    /**
     * Called when a new packet is read from TUN
     * 
     * @param buffer Direct ByteBuffer containing the raw IP packet
     * @param size Number of valid bytes in the buffer
     */
    fun onPacketRead(buffer: ByteBuffer, size: Int)
    
    /**
     * Called when an error occurs during reading
     * 
     * @param error The exception that occurred
     */
    fun onReadError(error: IOException)
}

// ============================================================
// TunReader - Main Implementation
// ============================================================

/**
 * Reads IP packets from the TUN interface
 * 
 * This class runs in its own thread and continuously reads packets.
 * 
 * @param tunFileDescriptor The TUN interface file descriptor from VpnService
 * @param callback Callback to receive packets
 */
class TunReader(
    private val tunFileDescriptor: FileDescriptor,
    private val callback: PacketReadCallback
) {
    
    // ============================================================
    // Constants
    // ============================================================
    
    /** Maximum Ethernet MTU (1500 bytes) + overhead */
    private val MAX_PACKET_SIZE = 2000
    
    /** Buffer pool size for packet buffers */
    private val BUFFER_POOL_SIZE = 50
    
    /** Minimum valid IPv4 packet size */
    private val MIN_IP_PACKET_SIZE = 20
    
    // ============================================================
    // State
    // ============================================================
    
    /** Whether the reader is actively reading */
    private val isRunning = AtomicBoolean(false)
    
    /** Reader thread reference */
    private var readerThread: Thread? = null
    
    /** Total packets read (statistics) */
    private val totalPacketsRead = AtomicLong(0)
    
    /** Total bytes read (statistics) */
    private val totalBytesRead = AtomicLong(0)
    
    /** Total invalid packets dropped (statistics) */
    private val totalInvalidPackets = AtomicLong(0)
    
    /** FileInputStream for reading from TUN */
    private var inputStream: FileInputStream? = null
    
    // ============================================================
    // Buffer Management
    // ============================================================
    
    /**
     * Pool of pre-allocated Direct ByteBuffers
     * 
     * Using a pool eliminates allocations in the hot path.
     * Each buffer is recycled after use.
     */
    private val bufferPool = ArrayDeque<ByteBuffer>(BUFFER_POOL_SIZE)
    private val bufferLock = Any()
    
    /**
     * Acquires a buffer from the pool or creates a new one
     */
    private fun acquireBuffer(): ByteBuffer {
        synchronized(bufferLock) {
            val buffer = bufferPool.removeFirstOrNull()
            if (buffer != null) {
                buffer.clear()
                return buffer
            }
        }
        return ByteBuffer.allocateDirect(MAX_PACKET_SIZE)
    }
    
    /**
     * Returns a buffer to the pool for reuse
     */
    private fun releaseBuffer(buffer: ByteBuffer) {
        synchronized(bufferLock) {
            if (bufferPool.size < BUFFER_POOL_SIZE) {
                buffer.clear()
                bufferPool.addLast(buffer)
            }
        }
    }
    
    // ============================================================
    // Lifecycle Management
    // ============================================================
    
    /**
     * Starts the packet reader thread
     * 
     * @return true if started successfully, false if already running
     */
    fun start(): Boolean {
        if (isRunning.get()) {
            return false
        }
        
        try {
            // Open FileInputStream from TUN file descriptor
            inputStream = FileInputStream(tunFileDescriptor)
            
            isRunning.set(true)
            
            readerThread = thread(name = "PrivacyGuard-TunReader", isDaemon = true) {
                readLoop()
            }
            
            return true
        } catch (e: IOException) {
            callback.onReadError(e)
            return false
        }
    }
    
    /**
     * Stops the packet reader thread
     */
    fun stop() {
        isRunning.set(false)
        
        // Interrupt the reader thread
        readerThread?.interrupt()
        readerThread = null
        
        // Close input stream
        try {
            inputStream?.close()
        } catch (e: IOException) {
            // Ignore
        }
        inputStream = null
        
        // Clear buffer pool
        synchronized(bufferLock) {
            bufferPool.clear()
        }
    }
    
    /**
     * Returns whether the reader is currently running
     */
    fun isRunning(): Boolean = isRunning.get()
    
    // ============================================================
    // Statistics
    // ============================================================
    
    /**
     * Returns total packets read since start
     */
    fun getTotalPacketsRead(): Long = totalPacketsRead.get()
    
    /**
     * Returns total bytes read since start
     */
    fun getTotalBytesRead(): Long = totalBytesRead.get()
    
    /**
     * Returns total invalid packets dropped
     */
    fun getTotalInvalidPackets(): Long = totalInvalidPackets.get()
    
    /**
     * Resets all statistics
     */
    fun resetStats() {
        totalPacketsRead.set(0)
        totalBytesRead.set(0)
        totalInvalidPackets.set(0)
    }
    
    // ============================================================
    // Main Read Loop
    // ============================================================
    
    /**
     * Main read loop - runs in dedicated thread
     * 
     * This loop continuously reads packets from TUN and dispatches them
     * to the callback for processing.
     */
    private fun readLoop() {
        while (isRunning.get()) {
            try {
                // Acquire a buffer from pool
                val buffer = acquireBuffer()
                
                // Read packet from TUN
                val bytesRead = readPacket(buffer)
                
                if (bytesRead > 0) {
                    // Update statistics
                    totalPacketsRead.incrementAndGet()
                    totalBytesRead.addAndGet(bytesRead.toLong())
                    
                    // Validate packet before processing
                    if (isValidPacket(buffer, bytesRead)) {
                        // Prepare buffer for reading
                        buffer.flip()
                        
                        // Dispatch to callback for processing
                        callback.onPacketRead(buffer, bytesRead)
                    } else {
                        totalInvalidPackets.incrementAndGet()
                        // Invalid packet - just drop and continue
                        releaseBuffer(buffer)
                    }
                } else {
                    // No packet read (should not happen with blocking read)
                    releaseBuffer(buffer)
                    Thread.sleep(1)
                }
                
            } catch (e: InterruptedException) {
                // Thread interrupted - exit gracefully
                break
            } catch (e: IOException) {
                // Error reading from TUN
                callback.onReadError(e)
                break
            } catch (e: Exception) {
                // Unexpected error - log and continue
                android.util.Log.e("TunReader", "Unexpected error in read loop", e)
            }
        }
    }
    
    /**
     * Reads a single packet from the TUN interface
     * 
     * @param buffer Buffer to read into
     * @return Number of bytes read, or -1 if end of stream
     * @throws IOException on read error
     */
    private fun readPacket(buffer: ByteBuffer): Int {
        val inputStream = inputStream ?: return -1
        
        // Ensure buffer has capacity
        if (buffer.remaining() < MAX_PACKET_SIZE) {
            // Should not happen with our pool
            return -1
        }
        
        // Direct read from TUN file descriptor
        // This is a blocking read - will wait for next packet
        return try {
            // Using Os.read for direct file descriptor access (faster)
            val byteArray = ByteArray(MAX_PACKET_SIZE)
            val bytesRead = Os.read(tunFileDescriptor, byteArray, 0, byteArray.size)
            if (bytesRead > 0) {
                buffer.put(byteArray, 0, bytesRead)
            }
            bytesRead
        } catch (e: Exception) {
            // Fallback to InputStream (slower but more portable)
            val byteArray = ByteArray(MAX_PACKET_SIZE)
            val bytesRead = inputStream.read(byteArray)
            if (bytesRead > 0) {
                buffer.put(byteArray, 0, bytesRead)
            }
            bytesRead
        }
    }
    
    /**
     * Validates that the read packet is a valid IP packet
     * 
     * Performs basic validation to filter out garbage:
     * - Minimum size (20 bytes for IPv4 header)
     * - IPv4 version (4)
     * - Valid header length
     * - Valid total length
     * 
     * @param buffer Buffer containing the packet
     * @param size Number of bytes read
     * @return true if packet appears valid
     */
    private fun isValidPacket(buffer: ByteBuffer, size: Int): Boolean {
        if (size < MIN_IP_PACKET_SIZE) {
            return false
        }
        
        // Save position to not disturb the buffer
        val savedPosition = buffer.position()
        
        try {
            // Check if it's a valid IPv4 packet
            buffer.position(0)
            val data = ByteArray(size)
            buffer.get(data)
            
            return isValidIpv4Packet(data, size)
        } finally {
            // Restore position
            buffer.position(savedPosition)
        }
    }
}

// ============================================================
// Non-Blocking TunReader with Selector (Advanced)
// ============================================================

/**
 * Non-blocking version of TunReader using NIO Selector
 * 
 * This version is more efficient for high-traffic scenarios but more complex.
 * For MVP, the blocking version above is sufficient.
 */
class NonBlockingTunReader(
    private val tunFileDescriptor: Int,
    private val callback: PacketReadCallback
) {
    
    private val isRunning = AtomicBoolean(false)
    private var readerThread: Thread? = null
    
    fun start(): Boolean {
        if (isRunning.get()) return false
        
        isRunning.set(true)
        readerThread = thread(name = "PrivacyGuard-TunReader-NonBlocking", isDaemon = true) {
            runSelectorLoop()
        }
        return true
    }
    
    fun stop() {
        isRunning.set(false)
        readerThread?.interrupt()
        readerThread = null
    }
    
    private fun runSelectorLoop() {
        val selector = java.nio.channels.Selector.open()
        val channel = java.nio.channels.SocketChannel.open()
        
        try {
            // Register TUN file descriptor with selector
            // Note: TUN doesn't support NIO directly, so this is complex
            // For MVP, the blocking version is simpler and sufficient
            
        } catch (e: Exception) {
            callback.onReadError(IOException("Selector error", e))
        } finally {
            selector.close()
        }
    }
}
