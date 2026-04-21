/**
 * SessionKey.kt
 *
 * 🔴 CRITICAL: This defines how connections are uniquely identified
 *
 * The 5-tuple (srcIp, srcPort, dstIp, dstPort, protocol) is the standard way
 * to uniquely identify a TCP/UDP connection. This is used as the key in SessionTable.
 *
 * Why 5-tuple?
 * =============
 * - srcIp + srcPort: Identifies the client (which app on which device)
 * - dstIp + dstPort: Identifies the server (where the app is connecting)
 * - protocol: Distinguishes TCP from UDP (same ports can be used for both)
 *
 * Without the 5-tuple, two different connections could be confused.
 * Example: Chrome to google.com:443 (TCP) and Chrome to google.com:443 (UDP QUIC)
 * Same IPs and ports, different protocols - must be treated separately.
 *
 * Performance Requirements:
 * =========================
 * - hashCode() must be fast (called for every packet lookup)
 * - equals() must be fast (called on hash collisions)
 * - Immutable (so hashCode is stable)
 *
 * Memory Requirements:
 * ====================
 * - Each SessionKey is stored in ConcurrentHashMap
 * - 10,000 active connections = 10,000 SessionKey objects
 * - Each SessionKey: 5 Ints (20 bytes) + object overhead (~24 bytes) = ~44 bytes
 * - Total: ~440KB for 10,000 connections (acceptable)
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.session

import java.nio.ByteBuffer

/**
 * Uniquely identifies a network connection using the standard 5-tuple.
 *
 * This class is immutable by design - all fields are 'val'.
 * This guarantees that hashCode() returns a consistent value.
 *
 * @property srcIp Source IP address (network byte order, as Int)
 * @property srcPort Source port (1-65535)
 * @property dstIp Destination IP address (network byte order, as Int)
 * @property dstPort Destination port (1-65535)
 * @property protocol Protocol number (6=TCP, 17=UDP)
 */
data class SessionKey(
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
     * Returns true if this is a valid connection (ports in range 1-65535)
     */
    fun isValid(): Boolean {
        return srcPort in 1..65535 && dstPort in 1..65535 &&
                (protocol == 6 || protocol == 17)
    }

    /**
     * Creates the reverse key for response lookup
     *
     * When a response comes from the server, source and destination are swapped.
     *
     * Example:
     * - Request: 10.0.0.2:12345 → 8.8.8.8:443
     * - Response: 8.8.8.8:443 → 10.0.0.2:12345
     *
     * This method creates the key we use to look up the original session.
     */
    fun reverse(): SessionKey {
        return SessionKey(
            srcIp = dstIp,
            srcPort = dstPort,
            dstIp = srcIp,
            dstPort = srcPort,
            protocol = protocol
        )
    }

    /**
     * Returns a 64-bit combined key for faster hashing in some contexts
     *
     * Combines srcIp and dstIp into a single Long, and srcPort/dstPort into another Int.
     * Useful for certain optimization scenarios.
     */
    fun toCompositeKey(): Long {
        val ipPart = (srcIp.toLong() shl 32) or (dstIp.toLong() and 0xFFFFFFFFL)
        val portPart = (srcPort shl 16) or dstPort
        return ipPart xor (portPart.toLong() shl 16) xor protocol.toLong()
    }

    /**
     * Converts to a byte array for storage or logging
     */
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(20)  // 4+2+4+2+4 = 16 + 4 for protocol = 20
        buffer.putInt(srcIp)
        buffer.putShort(srcPort.toShort())
        buffer.putInt(dstIp)
        buffer.putShort(dstPort.toShort())
        buffer.putInt(protocol)
        return buffer.array()
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
         * Creates a SessionKey from raw IP header bytes
         *
         * This is the primary factory method used during packet processing.
         * Called for every packet (1000+ times per second).
         *
         * @param srcIpBytes Source IP as 4-byte array (from IP header)
         * @param srcPort Source port (from TCP/UDP header)
         * @param dstIpBytes Destination IP as 4-byte array (from IP header)
         * @param dstPort Destination port (from TCP/UDP header)
         * @param protocol Protocol number (from IP header)
         */
        fun fromBytes(
            srcIpBytes: ByteArray,
            srcPort: Int,
            dstIpBytes: ByteArray,
            dstPort: Int,
            protocol: Int
        ): SessionKey {
            val srcIp = ((srcIpBytes[0].toInt() and 0xFF) shl 24) or
                    ((srcIpBytes[1].toInt() and 0xFF) shl 16) or
                    ((srcIpBytes[2].toInt() and 0xFF) shl 8) or
                    (srcIpBytes[3].toInt() and 0xFF)

            val dstIp = ((dstIpBytes[0].toInt() and 0xFF) shl 24) or
                    ((dstIpBytes[1].toInt() and 0xFF) shl 16) or
                    ((dstIpBytes[2].toInt() and 0xFF) shl 8) or
                    (dstIpBytes[3].toInt() and 0xFF)

            return SessionKey(srcIp, srcPort, dstIp, dstPort, protocol)
        }

        /**
         * Creates a SessionKey from integer IP addresses
         */
        fun fromInts(
            srcIp: Int,
            srcPort: Int,
            dstIp: Int,
            dstPort: Int,
            protocol: Int
        ): SessionKey {
            return SessionKey(srcIp, srcPort, dstIp, dstPort, protocol)
        }

        /**
         * Creates a SessionKey from a byte array (inverse of toByteArray)
         */
        fun fromByteArray(data: ByteArray): SessionKey? {
            if (data.size < 20) return null
            val buffer = ByteBuffer.wrap(data)
            val srcIp = buffer.int
            val srcPort = buffer.short.toInt() and 0xFFFF
            val dstIp = buffer.int
            val dstPort = buffer.short.toInt() and 0xFFFF
            val protocol = buffer.int
            return SessionKey(srcIp, srcPort, dstIp, dstPort, protocol)
        }

        /**
         * Creates a SessionKey for a DNS query (simplified)
         */
        fun forDns(clientIp: Int, clientPort: Int): SessionKey {
            // DNS typically goes to 8.8.8.8:53 or 1.1.1.1:53
            val dnsIp = (8 shl 24) or (8 shl 16) or (8 shl 8) or 8  // 8.8.8.8
            return SessionKey(clientIp, clientPort, dnsIp, 53, 17)  // UDP
        }
    }
}

/**
 * Extension function to create a SessionKey from a ConnectionKey (for backward compatibility)
 */
fun SessionKey.toConnectionKey(): ConnectionKey {
    return ConnectionKey(srcIp, srcPort, dstIp, dstPort, protocol)
}

/**
 * Extension function to create a SessionKey from a ConnectionKey
 */
fun ConnectionKey.toSessionKey(): SessionKey {
    return SessionKey(srcIp, srcPort, dstIp, dstPort, protocol)
}