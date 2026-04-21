/**
 * ByteUtils.kt
 *
 * Byte manipulation utilities for packet processing
 *
 * What it does:
 * =============
 * Provides fast, safe byte operations for network packet manipulation:
 * - Reading/writing integers from byte arrays (big-endian, network byte order)
 * - Converting between byte arrays and primitives
 * - Buffer pooling for zero-allocation packet processing
 * - Safe bounds checking
 *
 * Performance Requirements:
 * =========================
 * - All operations must be inlined where possible
 * - No object allocations in hot path
 * - Bounds checking is essential for security
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ============================================================
// Constants
// ============================================================

/** Size of an IPv4 header (minimum, no options) */
const val IP_HEADER_MIN_SIZE = 20

/** Size of a TCP header (minimum, no options) */
const val TCP_HEADER_MIN_SIZE = 20

/** Size of a UDP header */
const val UDP_HEADER_SIZE = 8

/** Maximum MTU for Ethernet (1500 bytes) */
const val MAX_MTU = 1500

/** Maximum packet size we handle (64KB) */
const val MAX_PACKET_SIZE = 65535

// ============================================================
// Read Operations (Big-Endian / Network Byte Order)
// ============================================================

/**
 * Reads a 16-bit unsigned integer from a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @return 16-bit value (0-65535)
 * @throws IndexOutOfBoundsException if offset + 2 > data.size
 */
inline fun readUint16(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}

/**
 * Reads a 32-bit unsigned integer from a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @return 32-bit value (0-4294967295) as Long
 * @throws IndexOutOfBoundsException if offset + 4 > data.size
 */
inline fun readUint32(data: ByteArray, offset: Int): Long {
    return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
}

/**
 * Reads a 16-bit signed integer from a byte array (big-endian)
 */
inline fun readInt16(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() shl 8) or (data[offset + 1].toInt() and 0xFF)
}

/**
 * Reads a 32-bit signed integer from a byte array (big-endian)
 */
inline fun readInt32(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}

// ============================================================
// Write Operations (Big-Endian / Network Byte Order)
// ============================================================

/**
 * Writes a 16-bit value to a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @param value 16-bit value (0-65535)
 * @throws IndexOutOfBoundsException if offset + 2 > data.size
 */
inline fun writeUint16(data: ByteArray, offset: Int, value: Int) {
    data[offset] = ((value ushr 8) and 0xFF).toByte()
    data[offset + 1] = (value and 0xFF).toByte()
}

/**
 * Writes a 32-bit value to a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @param value 32-bit value (0-4294967295) as Long
 * @throws IndexOutOfBoundsException if offset + 4 > data.size
 */
inline fun writeUint32(data: ByteArray, offset: Int, value: Long) {
    data[offset] = ((value ushr 24) and 0xFF).toByte()
    data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    data[offset + 3] = (value and 0xFF).toByte()
}

/**
 * Writes a 16-bit signed integer to a byte array (big-endian)
 */
inline fun writeInt16(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 8).toByte()
    data[offset + 1] = value.toByte()
}

/**
 * Writes a 32-bit signed integer to a byte array (big-endian)
 */
inline fun writeInt32(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 24).toByte()
    data[offset + 1] = (value ushr 16).toByte()
    data[offset + 2] = (value ushr 8).toByte()
    data[offset + 3] = value.toByte()
}

// ============================================================
// Safe Copy Operations
// ============================================================

/**
 * Copies a range from source to destination with bounds checking
 *
 * @return true if copy succeeded, false if out of bounds
 */
fun safeCopy(src: ByteArray, srcOffset: Int, dst: ByteArray, dstOffset: Int, length: Int): Boolean {
    if (srcOffset < 0 || dstOffset < 0 || length < 0) return false
    if (srcOffset + length > src.size) return false
    if (dstOffset + length > dst.size) return false

    System.arraycopy(src, srcOffset, dst, dstOffset, length)
    return true
}

/**
 * Creates a copy of a byte array range
 */
fun copyOfRange(data: ByteArray, start: Int, end: Int): ByteArray {
    val length = end - start
    val result = ByteArray(length)
    System.arraycopy(data, start, result, 0, length)
    return result
}

// ============================================================
// Buffer Pool (Zero-Allocation Packet Processing)
// ============================================================

/**
 * Pool of Direct ByteBuffers for zero-allocation packet processing
 *
 * This pool reuses buffers to avoid GC pressure at 10,000+ packets/sec
 *
 * Usage:
 * ```
 * val buffer = BufferPool.acquire()
 * try {
 *     // Use buffer
 * } finally {
 *     BufferPool.release(buffer)
 * }
 * ```
 */
object BufferPool {
    private const val POOL_SIZE = 100
    private const val BUFFER_SIZE = MAX_PACKET_SIZE

    private val pool = ArrayDeque<ByteBuffer>(POOL_SIZE)
    private val lock = Any()

    /**
     * Acquires a Direct ByteBuffer from the pool
     *
     * @return Direct ByteBuffer ready for use (position = 0, limit = capacity)
     */
    fun acquire(): ByteBuffer {
        synchronized(lock) {
            val buffer = pool.poll()
            if (buffer != null) {
                buffer.clear()
                return buffer
            }
        }
        return ByteBuffer.allocateDirect(BUFFER_SIZE)
    }

    /**
     * Releases a Direct ByteBuffer back to the pool
     *
     * @param buffer The buffer to release (will be cleared)
     */
    fun release(buffer: ByteBuffer) {
        synchronized(lock) {
            if (pool.size < POOL_SIZE) {
                buffer.clear()
                pool.add(buffer)
            }
        }
    }

    /**
     * Runs a block with an acquired buffer, automatically releasing it
     */
    inline fun <T> withBuffer(block: (ByteBuffer) -> T): T {
        val buffer = acquire()
        try {
            return block(buffer)
        } finally {
            release(buffer)
        }
    }
}

// ============================================================
// Hex Utilities (for Debugging)
// ============================================================

/**
 * Converts bytes to hex string for debugging
 *
 * Example: byteArrayOf(0x48, 0x65) -> "48 65"
 *
 * WARNING: This is SLOW. Only use for debugging, never in packet processing.
 */
fun bytesToHex(bytes: ByteArray, limit: Int = bytes.size): String {
    val sb = StringBuilder()
    for (i in 0 until minOf(limit, bytes.size)) {
        if (i > 0) sb.append(' ')
        sb.append(String.format("%02X", bytes[i] and 0xFF))
    }
    if (limit < bytes.size) {
        sb.append(" ... (${bytes.size - limit} more)")
    }
    return sb.toString()
}

/**
 * Converts hex string to bytes
 *
 * Example: "48 65" -> byteArrayOf(0x48, 0x65)
 */
fun hexToBytes(hex: String): ByteArray? {
    val cleaned = hex.replace(" ", "").replace("\n", "")
    if (cleaned.length % 2 != 0) return null

    val result = ByteArray(cleaned.length / 2)
    for (i in result.indices) {
        val index = i * 2
        val value = cleaned.substring(index, index + 2).toIntOrNull(16) ?: return null
        result[i] = value.toByte()
    }
    return result
}

// ============================================================
// Packet Validation
// ============================================================

/**
 * Validates that a packet has a valid IPv4 header
 *
 * @param data Packet bytes
 * @param length Packet length
 * @return true if packet appears to be valid IPv4
 */
fun isValidIpv4Packet(data: ByteArray, length: Int): Boolean {
    if (length < IP_HEADER_MIN_SIZE) return false

    val version = (data[0].toInt() ushr 4) and 0x0F
    if (version != 4) return false

    val headerLength = (data[0].toInt() and 0x0F) * 4
    if (headerLength < IP_HEADER_MIN_SIZE || headerLength > 60) return false
    if (headerLength > length) return false

    val totalLength = readUint16(data, 2)
    if (totalLength < headerLength || totalLength > length) return false

    return true
}

/**
 * Validates that a packet has a valid TCP header
 */
fun isValidTcpPacket(data: ByteArray, offset: Int, length: Int): Boolean {
    if (length - offset < TCP_HEADER_MIN_SIZE) return false

    val dataOffset = ((data[offset + 12].toInt() ushr 4) and 0x0F) * 4
    if (dataOffset < TCP_HEADER_MIN_SIZE || dataOffset > 60) return false
    if (offset + dataOffset > length) return false

    return true
}

/**
 * Validates that a packet has a valid UDP header
 */
fun isValidUdpPacket(data: ByteArray, offset: Int, length: Int): Boolean {
    if (length - offset < UDP_HEADER_SIZE) return false

    val udpLength = readUint16(data, offset + 4)
    if (udpLength < UDP_HEADER_SIZE) return false
    if (offset + udpLength > length) return false

    return true
}