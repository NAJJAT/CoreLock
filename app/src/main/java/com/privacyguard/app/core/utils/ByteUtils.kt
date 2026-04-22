/**
 * ByteUtils.kt
 *
 * Byte manipulation utilities for network packet processing
 *
 * What it does:
 * =============
 * Provides fast, safe operations for reading and writing bytes from/to arrays.
 * All operations are network byte order (big-endian) as required by TCP/IP.
 *
 * Why this is critical:
 * =====================
 * - Packet parsing requires reading IP addresses (4 bytes), ports (2 bytes),
 *   sequence numbers (4 bytes), etc.
 * - Packet modification requires writing these values back
 * - Performance is critical (10,000+ packets/sec)
 * - Bounds checking prevents security vulnerabilities
 *
 * Performance Requirements:
 * =========================
 * - All functions are inline (no function call overhead)
 * - No object allocations in hot path
 * - Bounds checking is optional in production (can be disabled for speed)
 * - Direct memory access where possible
 *
 * Thread Safety:
 * ==============
 * All functions are stateless and thread-safe.
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.utils

import java.nio.ByteBuffer

// ============================================================
// Constants
// ============================================================

/** Size of an IPv4 address in bytes */
const val IPV4_SIZE = 4

/** Size of an IPv6 address in bytes */
const val IPV6_SIZE = 16

/** Size of a 16-bit port number (2 bytes) */
const val PORT_SIZE = 2

/** Size of a 32-bit sequence number (4 bytes) */
const val SEQ_SIZE = 4

/** Maximum size of a DNS domain name (255 bytes) */
const val MAX_DOMAIN_SIZE = 255

// ============================================================
// Read Operations (Big-Endian / Network Byte Order)
// ============================================================

/**
 * Reads a single byte from a byte array
 *
 * @param data Byte array
 * @param offset Starting offset
 * @return Byte value (0-255) as Int
 * @throws IndexOutOfBoundsException if offset >= data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun readByte(data: ByteArray, offset: Int): Int {
    return data[offset].toInt() and 0xFF
}

/**
 * Reads a 16-bit unsigned integer from a byte array (big-endian)
 *
 * This is the standard network byte order for:
 * - Source/destination ports (TCP/UDP)
 * - IP header length
 * - UDP length
 * - TCP window size
 * - DNS query ID
 *
 * Example:
 * - Bytes: [0x1F, 0x90] -> 8080 (HTTP port)
 * - Bytes: [0x00, 0x35] -> 53 (DNS port)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @return 16-bit value (0-65535) as Int
 * @throws IndexOutOfBoundsException if offset + 2 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun readUint16(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}

/**
 * Reads a 16-bit signed integer from a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @return 16-bit value (-32768 to 32767) as Int
 * @throws IndexOutOfBoundsException if offset + 2 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun readInt16(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() shl 8) or (data[offset + 1].toInt() and 0xFF)
}

/**
 * Reads a 24-bit unsigned integer from a byte array (big-endian)
 *
 * Used for some DNS record types and protocol extensions.
 *
 * @param data Byte array
 * @param offset Starting offset
 * @return 24-bit value (0-16777215) as Int
 * @throws IndexOutOfBoundsException if offset + 3 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun readUint24(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)
}

/**
 * Reads a 32-bit unsigned integer from a byte array (big-endian)
 *
 * Used for:
 * - TCP sequence numbers
 * - TCP acknowledgment numbers
 * - IP addresses
 * - DNS TTL values
 *
 * Example:
 * - Bytes: [192, 168, 1, 1] -> 3232235521 (IP address)
 * - Bytes: [0x12, 0x34, 0x56, 0x78] -> 305419896 (sequence number)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @return 32-bit value (0-4294967295) as Long
 * @throws IndexOutOfBoundsException if offset + 4 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun readUint32(data: ByteArray, offset: Int): Long {
    return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
}

/**
 * Reads a 32-bit signed integer from a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @return 32-bit value as Int
 * @throws IndexOutOfBoundsException if offset + 4 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun readInt32(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}

/**
 * Reads a 64-bit unsigned integer from a byte array (big-endian)
 *
 * Used for some protocol extensions and timestamp options.
 *
 * @param data Byte array
 * @param offset Starting offset
 * @return 64-bit value (0-2^64-1) as Long
 * @throws IndexOutOfBoundsException if offset + 8 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun readUint64(data: ByteArray, offset: Int): Long {
    return ((data[offset].toLong() and 0xFF) shl 56) or
            ((data[offset + 1].toLong() and 0xFF) shl 48) or
            ((data[offset + 2].toLong() and 0xFF) shl 40) or
            ((data[offset + 3].toLong() and 0xFF) shl 32) or
            ((data[offset + 4].toLong() and 0xFF) shl 24) or
            ((data[offset + 5].toLong() and 0xFF) shl 16) or
            ((data[offset + 6].toLong() and 0xFF) shl 8) or
            (data[offset + 7].toLong() and 0xFF)
}

/**
 * Reads an IPv4 address as Int (network byte order)
 *
 * Example: [192, 168, 1, 1] -> 3232235521
 *
 * @param data Byte array
 * @param offset Starting offset
 * @return IPv4 address as Int
 */
@Suppress("NOTHING_TO_INLINE")
inline fun readIpv4Address(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}

// ============================================================
// Write Operations (Big-Endian / Network Byte Order)
// ============================================================

/**
 * Writes a single byte to a byte array
 *
 * @param data Byte array
 * @param offset Starting offset
 * @param value Byte value (0-255)
 * @throws IndexOutOfBoundsException if offset >= data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun writeByte(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value and 0xFF).toByte()
}

/**
 * Writes a 16-bit value to a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @param value 16-bit value (0-65535)
 * @throws IndexOutOfBoundsException if offset + 2 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun writeUint16(data: ByteArray, offset: Int, value: Int) {
    data[offset] = ((value ushr 8) and 0xFF).toByte()
    data[offset + 1] = (value and 0xFF).toByte()
}

/**
 * Writes a 16-bit signed integer to a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @param value 16-bit value (-32768 to 32767)
 * @throws IndexOutOfBoundsException if offset + 2 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun writeInt16(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 8).toByte()
    data[offset + 1] = value.toByte()
}

/**
 * Writes a 24-bit value to a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @param value 24-bit value (0-16777215)
 * @throws IndexOutOfBoundsException if offset + 3 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun writeUint24(data: ByteArray, offset: Int, value: Int) {
    data[offset] = ((value ushr 16) and 0xFF).toByte()
    data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    data[offset + 2] = (value and 0xFF).toByte()
}

/**
 * Writes a 32-bit value to a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @param value 32-bit value (0-4294967295) as Long
 * @throws IndexOutOfBoundsException if offset + 4 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun writeUint32(data: ByteArray, offset: Int, value: Long) {
    data[offset] = ((value ushr 24) and 0xFF).toByte()
    data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    data[offset + 3] = (value and 0xFF).toByte()
}

/**
 * Writes a 32-bit signed integer to a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @param value 32-bit value as Int
 * @throws IndexOutOfBoundsException if offset + 4 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun writeInt32(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 24).toByte()
    data[offset + 1] = (value ushr 16).toByte()
    data[offset + 2] = (value ushr 8).toByte()
    data[offset + 3] = value.toByte()
}

/**
 * Writes a 64-bit value to a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @param value 64-bit value as Long
 * @throws IndexOutOfBoundsException if offset + 8 > data.size
 */
@Suppress("NOTHING_TO_INLINE")
inline fun writeUint64(data: ByteArray, offset: Int, value: Long) {
    data[offset] = ((value ushr 56) and 0xFF).toByte()
    data[offset + 1] = ((value ushr 48) and 0xFF).toByte()
    data[offset + 2] = ((value ushr 40) and 0xFF).toByte()
    data[offset + 3] = ((value ushr 32) and 0xFF).toByte()
    data[offset + 4] = ((value ushr 24) and 0xFF).toByte()
    data[offset + 5] = ((value ushr 16) and 0xFF).toByte()
    data[offset + 6] = ((value ushr 8) and 0xFF).toByte()
    data[offset + 7] = (value and 0xFF).toByte()
}

/**
 * Writes an IPv4 address to a byte array (big-endian)
 *
 * @param data Byte array
 * @param offset Starting offset
 * @param ip IPv4 address as Int
 */
@Suppress("NOTHING_TO_INLINE")
inline fun writeIpv4Address(data: ByteArray, offset: Int, ip: Int) {
    data[offset] = ((ip ushr 24) and 0xFF).toByte()
    data[offset + 1] = ((ip ushr 16) and 0xFF).toByte()
    data[offset + 2] = ((ip ushr 8) and 0xFF).toByte()
    data[offset + 3] = (ip and 0xFF).toByte()
}

// ============================================================
// Safe Copy Operations (with bounds checking)
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
 *
 * @return New byte array containing the specified range
 */
fun copyOfRange(data: ByteArray, start: Int, end: Int): ByteArray {
    val length = end - start
    val result = ByteArray(length)
    System.arraycopy(data, start, result, 0, length)
    return result
}

/**
 * Creates a copy of a byte array with safe bounds
 *
 * @return New byte array containing the specified range, or null if invalid
 */
fun copyOfRangeSafe(data: ByteArray, start: Int, end: Int): ByteArray? {
    if (start < 0 || end < start || end > data.size) return null
    return copyOfRange(data, start, end)
}

// ============================================================
// IP Address Utilities
// ============================================================

/**
 * Converts an integer IP address to dotted decimal string
 *
 * Example: 3232235521 -> "192.168.1.1"
 *
 * WARNING: This is SLOW. Only use for logging/UI, never for packet processing.
 *
 * @param ip IP address as integer (network byte order)
 * @return Dotted decimal string
 */
fun ipToString(ip: Int): String {
    return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
}

/**
 * Converts a dotted decimal IP string to integer
 *
 * Example: "192.168.1.1" -> 3232235521
 *
 * @param ipString IP address as string
 * @return IP address as integer, or null if invalid
 */
fun stringToIp(ipString: String): Int? {
    val parts = ipString.split('.')
    if (parts.size != 4) return null

    try {
        var result = 0
        for (i in 0..3) {
            val part = parts[i].toInt()
            if (part !in 0..255) return null
            result = (result shl 8) or part
        }
        return result
    } catch (e: NumberFormatException) {
        return null
    }
}

/**
 * Validates an IPv4 address string
 */
fun isValidIpv4(ipString: String): Boolean {
    return stringToIp(ipString) != null
}

// ============================================================
// Port Utilities
// ============================================================

/**
 * Validates a port number (1-65535)
 */
fun isValidPort(port: Int): Boolean {
    return port in 1..65535
}

/**
 * Returns the protocol name for a port number
 */
fun getProtocolName(port: Int): String {
    return when (port) {
        20, 21 -> "FTP"
        22 -> "SSH"
        23 -> "Telnet"
        25 -> "SMTP"
        53 -> "DNS"
        80 -> "HTTP"
        110 -> "POP3"
        123 -> "NTP"
        143 -> "IMAP"
        443 -> "HTTPS"
        465 -> "SMTPS"
        993 -> "IMAPS"
        995 -> "POP3S"
        3306 -> "MySQL"
        3389 -> "RDP"
        5432 -> "PostgreSQL"
        8080 -> "HTTP-Alt"
        else -> "Unknown"
    }
}

// ============================================================
// Byte Array Comparison
// ============================================================

/**
 * Compares two byte arrays for equality with constant time (for security)
 *
 * This prevents timing attacks when comparing sensitive data like passwords.
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false

    var result = 0
    for (i in a.indices) {
        result = result or (a[i].toInt() xor b[i].toInt())
    }
    return result == 0
}

/**
 * Checks if a byte array starts with a prefix
 */
fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
    if (data.size < prefix.size) return false
    for (i in prefix.indices) {
        if (data[i] != prefix[i]) return false
    }
    return true
}

// ============================================================
// Buffer Pool (Zero-Allocation Packet Processing)
// ============================================================

/**
 * Pool of Direct ByteBuffers for zero-allocation packet processing
 *
 * This pool reuses buffers to avoid GC pressure at 10,000+ packets/sec.
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
    private const val BUFFER_SIZE = 65535  // Max IP packet size

    private val pool = ArrayDeque<ByteBuffer>(POOL_SIZE)
    private val lock = Any()

    /**
     * Acquires a Direct ByteBuffer from the pool
     *
     * @return Direct ByteBuffer ready for use (position = 0, limit = capacity)
     */
    fun acquire(): ByteBuffer {
        synchronized(lock) {
            val buffer = pool.removeFirstOrNull()
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
                pool.addLast(buffer)
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
// Hex Utilities (Debugging Only)
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
        sb.append(String.format("%02X", bytes[i].toInt() and 0xFF))
    }
    if (limit < bytes.size) {
        sb.append(" ... (${bytes.size - limit} more)")
    }
    return sb.toString()
}

/**
 * Converts bytes to hex string without spaces (compact format)
 */
fun bytesToHexCompact(bytes: ByteArray): String {
    val sb = StringBuilder()
    for (b in bytes) {
        sb.append(String.format("%02X", b.toInt() and 0xFF))
    }
    return sb.toString()
}

/**
 * Converts hex string to bytes
 *
 * Example: "48 65" -> byteArrayOf(0x48, 0x65)
 */
fun hexToBytes(hex: String): ByteArray? {
    val cleaned = hex.replace(" ", "").replace("\n", "").replace("\t", "")
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
    if (length < 20) return false

    val version = (data[0].toInt() ushr 4) and 0x0F
    if (version != 4) return false

    val headerLength = (data[0].toInt() and 0x0F) * 4
    if (headerLength < 20 || headerLength > 60) return false
    if (headerLength > length) return false

    val totalLength = readUint16(data, 2)
    if (totalLength < headerLength || totalLength > length) return false

    return true
}

/**
 * Validates that a packet has a valid TCP header
 */
fun isValidTcpPacket(data: ByteArray, offset: Int, length: Int): Boolean {
    if (length - offset < 20) return false

    val dataOffset = ((data[offset + 12].toInt() ushr 4) and 0x0F) * 4
    if (dataOffset < 20 || dataOffset > 60) return false
    if (offset + dataOffset > length) return false

    return true
}

/**
 * Validates that a packet has a valid UDP header
 */
fun isValidUdpPacket(data: ByteArray, offset: Int, length: Int): Boolean {
    if (length - offset < 8) return false

    val udpLength = readUint16(data, offset + 4)
    if (udpLength < 8) return false
    if (offset + udpLength > length) return false

    return true
}

// ============================================================
// ByteArray Extensions (Convenience)
// ============================================================

/**
 * Returns a hex string representation of this byte array
 */
fun ByteArray.toHex(limit: Int = this.size): String = bytesToHex(this, limit)

/**
 * Returns a compact hex string representation
 */
fun ByteArray.toHexCompact(): String = bytesToHexCompact(this)
