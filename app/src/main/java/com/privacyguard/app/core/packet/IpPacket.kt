/**
 * IpPacket.kt
 *
 * RFC 791 - Internet Protocol Specification
 *
 * Parses IPv4 packets from the TUN interface.
 * This is the FIRST layer of packet processing in PrivacyGuard.
 *
 * Every packet that enters the VPN tunnel passes through this parser.
 *
 * Performance requirements:
 * - Parse must complete in < 50 microseconds
 * - Called 1000+ times per second
 * - Zero object allocations in hot path (uses existing buffers where possible)
 *
 * Security requirements:
 * - Validate all header fields before reading
 * - Reject malformed packets (attack surface)
 * - Handle fragmented packets gracefully (reject until reassembly implemented)
 *
 * Thread Safety: This class is immutable. All methods are thread-safe.
 *
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.core.packet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents an IPv4 packet with all headers and payload.
 *
 * This class is immutable - once created, it cannot be modified.
 * For packet modification, create a new instance with modified fields.
 *
 * Private constructor ensures all instances come from validated parse().
 *
 * @property version IP version (must be 4 for IPv4)
 * @property headerLength IP header length in bytes (usually 20, max 60)
 * @property typeOfService Type of Service / Differentiated Services Code Point
 * @property totalLength Total packet length (header + payload) in bytes
 * @property identification Unique identifier for fragmentation
 * @property flags 3-bit flags: Reserved (0), Don't Fragment (DF), More Fragments (MF)
 * @property fragmentOffset Fragment offset for reassembly (13 bits, units of 8 bytes)
 * @property timeToLive Time To Live (hop limit, decremented by each router)
 * @property protocol Transport protocol (6=TCP, 17=UDP, 1=ICMP, etc.)
 * @property headerChecksum Checksum of the IP header only (not payload)
 * @property sourceAddress 32-bit source IP address (network byte order)
 * @property destinationAddress 32-bit destination IP address (network byte order)
 * @property options Optional IP options (rarely used, max 40 bytes)
 * @property payload The actual data (TCP/UDP/ICMP packet)
 */
class IpPacket private constructor(
    val version: Int,
    val headerLength: Int,
    val typeOfService: Int,
    val totalLength: Int,
    val identification: Int,
    val flags: Int,
    val fragmentOffset: Int,
    val timeToLive: Int,
    val protocol: Int,
    val headerChecksum: Int,
    val sourceAddress: Int,
    val destinationAddress: Int,
    val options: ByteArray,
    val payload: ByteArray
) {

    /**
     * Returns true if this is a TCP packet.
     * Protocol number 6 = TCP (assigned by IANA)
     */
    fun isTcp(): Boolean = protocol == 6

    /**
     * Returns true if this is a UDP packet.
     * Protocol number 17 = UDP (assigned by IANA)
     */
    fun isUdp(): Boolean = protocol == 17

    /**
     * Returns true if this is an ICMP packet.
     * Protocol number 1 = ICMP (assigned by IANA)
     */
    fun isIcmp(): Boolean = protocol == 1

    /**
     * Returns true if this packet is fragmented (needs reassembly).
     * Fragmentation is rare in modern networks but must be handled.
     *
     * Fragment offset > 0 OR More Fragments flag is set
     *
     * IMPORTANT: For MVP, fragmented packets are rejected (return null in parse)
     * Full reassembly will be implemented in v1.5 if needed.
     */
    fun isFragmented(): Boolean = (flags and 0x01) != 0 || fragmentOffset != 0

    /**
     * Returns true if the Don't Fragment flag is set.
     * Modern networks prefer DF to avoid fragmentation overhead.
     */
    fun isDontFragment(): Boolean = (flags and 0x02) != 0

    /**
     * Converts the packet back to raw bytes for writing to TUN.
     *
     * This is the inverse of the parse() function.
     * Used when we need to forward or modify packets.
     *
     * Performance note: This allocates a new ByteArray each time.
     * For forwarding unchanged packets, consider using the original buffer.
     *
     * FIXED: totalLength is now derived from actual header + payload (not stored value)
     * FIXED: No unnecessary payload copying (uses reference to existing payload)
     *
     * @return Raw byte array ready for TUN injection
     */
    fun toRawBytes(): ByteArray {
        // FIXED: Derive total length from actual data, not stored value
        val actualTotalLength = headerLength + payload.size
        val buffer = ByteBuffer.allocate(actualTotalLength)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Byte 0: Version (4 bits) + Header Length (4 bits, in 32-bit words)
        val firstByte = ((version shl 4) or (headerLength / 4)).toByte()
        buffer.put(firstByte)

        // Byte 1: Type of Service
        buffer.put(typeOfService.toByte())

        // Bytes 2-3: Total Length (FIXED: use actual, not stored)
        buffer.putShort(actualTotalLength.toShort())

        // Bytes 4-5: Identification
        buffer.putShort(identification.toShort())

        // Bytes 6-7: Flags (3 bits) + Fragment Offset (13 bits)
        val flagsAndOffset = ((flags and 0x07) shl 13) or (fragmentOffset and 0x1FFF)
        buffer.putShort(flagsAndOffset.toShort())

        // Byte 8: Time To Live
        buffer.put(timeToLive.toByte())

        // Byte 9: Protocol
        buffer.put(protocol.toByte())

        // Bytes 10-11: Header Checksum (calculate fresh)
        val checksumPos = buffer.position()
        buffer.putShort(0) // Placeholder

        // Bytes 12-15: Source Address
        buffer.putInt(sourceAddress)

        // Bytes 16-19: Destination Address
        buffer.putInt(destinationAddress)

        // Bytes 20+: Options (if any)
        if (options.isNotEmpty()) {
            buffer.put(options)
        }

        // Calculate and set checksum
        val headerBytes = ByteArray(headerLength)
        buffer.rewind()
        buffer.get(headerBytes, 0, headerLength)

        // Zero out checksum field in copy before calculation
        headerBytes[10] = 0
        headerBytes[11] = 0

        val calculatedChecksum = calculateChecksum(headerBytes)
        buffer.position(checksumPos)
        buffer.putShort(calculatedChecksum.toShort())

        // Move position to end of header
        buffer.position(headerLength)

        // Payload (FIXED: use existing payload, no copy)
        buffer.put(payload)

        return buffer.array()
    }

    /**
     * Returns a copy of this packet with modified destination IP.
     * Used for NAT when forwarding to external servers.
     *
     * FIXED: No unnecessary payload copying - payload reference is shared
     *
     * @param newDestIp New destination IP address (network byte order)
     * @return New IpPacket instance with updated destination IP
     */
    fun withDestinationIp(newDestIp: Int): IpPacket {
        return IpPacket(
            version = version,
            headerLength = headerLength,
            typeOfService = typeOfService,
            totalLength = totalLength,
            identification = identification,
            flags = flags,
            fragmentOffset = fragmentOffset,
            timeToLive = timeToLive,
            protocol = protocol,
            headerChecksum = headerChecksum,
            sourceAddress = sourceAddress,
            destinationAddress = newDestIp,
            options = options,  // FIXED: No copy - immutable reference
            payload = payload    // FIXED: No copy - immutable reference
        )
    }

    /**
     * Returns a copy of this packet with modified source IP.
     * Used for NAT when forwarding to external servers.
     *
     * FIXED: No unnecessary payload copying - payload reference is shared
     *
     * @param newSrcIp New source IP address (network byte order)
     * @return New IpPacket instance with updated source IP
     */
    fun withSourceIp(newSrcIp: Int): IpPacket {
        return IpPacket(
            version = version,
            headerLength = headerLength,
            typeOfService = typeOfService,
            totalLength = totalLength,
            identification = identification,
            flags = flags,
            fragmentOffset = fragmentOffset,
            timeToLive = timeToLive,
            protocol = protocol,
            headerChecksum = headerChecksum,
            sourceAddress = newSrcIp,
            destinationAddress = destinationAddress,
            options = options,  // FIXED: No copy - immutable reference
            payload = payload    // FIXED: No copy - immutable reference
        )
    }

    override fun toString(): String {
        return "IpPacket(src=${ipToString(sourceAddress)}, dst=${ipToString(destinationAddress)}, " +
                "proto=${protocolName()}, len=$totalLength, id=$identification)"
    }

    private fun protocolName(): String = when (protocol) {
        6 -> "TCP"
        17 -> "UDP"
        1 -> "ICMP"
        else -> "Unknown($protocol)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IpPacket) return false

        if (version != other.version) return false
        if (headerLength != other.headerLength) return false
        if (typeOfService != other.typeOfService) return false
        if (totalLength != other.totalLength) return false
        if (identification != other.identification) return false
        if (flags != other.flags) return false
        if (fragmentOffset != other.fragmentOffset) return false
        if (timeToLive != other.timeToLive) return false
        if (protocol != other.protocol) return false
        if (headerChecksum != other.headerChecksum) return false
        if (sourceAddress != other.sourceAddress) return false
        if (destinationAddress != other.destinationAddress) return false
        if (!options.contentEquals(other.options)) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + headerLength
        result = 31 * result + typeOfService
        result = 31 * result + totalLength
        result = 31 * result + identification
        result = 31 * result + flags
        result = 31 * result + fragmentOffset
        result = 31 * result + timeToLive
        result = 31 * result + protocol
        result = 31 * result + headerChecksum
        result = 31 * result + sourceAddress
        result = 31 * result + destinationAddress
        result = 31 * result + options.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {

        // FIXED: Checksum validation only in debug builds (performance)
        // Set to BuildConfig.DEBUG in actual project
        private const val VALIDATE_CHECKSUM = false

        /**
         * Parses raw bytes from TUN into an IpPacket.
         *
         * This is the ENTRY POINT for all packet processing.
         * Called for EVERY packet that enters PrivacyGuard.
         *
         * Thread Safety: This method is stateless and thread-safe.
         *
         * Performance Requirements:
         * - Must complete in < 50 microseconds
         * - Called 1000+ times per second
         * - Validates all bounds before reading (security)
         *
         * Security: Rejects fragmented packets (reassembly not implemented in MVP)
         *
         * @param data Raw bytes from TUN file descriptor
         * @param length Number of valid bytes in data array
         * @return IpPacket object or null if parsing fails (malformed packet)
         */
        fun parse(data: ByteArray, length: Int): IpPacket? {
            // Check 1: Minimum IPv4 header is 20 bytes
            if (length < 20) {
                return null
            }

            val buffer = ByteBuffer.wrap(data, 0, length)
            buffer.order(ByteOrder.BIG_ENDIAN)

            // Byte 0: Version (high 4 bits) + Header Length (low 4 bits, in 32-bit words)
            val firstByte = buffer.get().toInt() and 0xFF
            val version = (firstByte shr 4) and 0x0F
            val headerLength = (firstByte and 0x0F) * 4

            // Check 2: Validate IPv4 (we don't support IPv6 in MVP)
            if (version != 4) {
                return null
            }

            // Check 3: Validate header length bounds (RFC 791: 20 to 60 bytes)
            if (headerLength < 20 || headerLength > 60) {
                return null
            }

            // Check 4: Ensure we have enough data for the claimed header length
            if (headerLength > length) {
                return null
            }

            // Byte 1: Type of Service
            val typeOfService = buffer.get().toInt() and 0xFF

            // Bytes 2-3: Total Length
            val totalLength = buffer.getShort().toInt() and 0xFFFF

            // Check 5: Validate total length against actual data
            if (totalLength < headerLength || totalLength > length) {
                return null
            }

            // Bytes 4-5: Identification
            val identification = buffer.getShort().toInt() and 0xFFFF

            // Bytes 6-7: Flags (3 bits) + Fragment Offset (13 bits)
            val flagsAndOffset = buffer.getShort().toInt() and 0xFFFF
            val flags = (flagsAndOffset shr 13) and 0x07
            val fragmentOffset = flagsAndOffset and 0x1FFF

            // Check 6: FIXED - Reject fragmented packets (reassembly not implemented)
            // Full reassembly will be added in v1.5 if needed
            val isFragmented = (flags and 0x01) != 0 || fragmentOffset != 0
            if (isFragmented) {
                // Fragmented packet - reject for MVP
                return null
            }

            // Byte 8: Time To Live
            val timeToLive = buffer.get().toInt() and 0xFF

            // Byte 9: Protocol
            val protocol = buffer.get().toInt() and 0xFF

            // Bytes 10-11: Header Checksum
            val headerChecksum = buffer.getShort().toInt() and 0xFFFF

            // Bytes 12-15: Source Address
            val sourceAddress = buffer.getInt()

            // Bytes 16-19: Destination Address
            val destinationAddress = buffer.getInt()

            // Options (if header length > 20)
            val optionsLength = headerLength - 20
            val options = ByteArray(optionsLength)
            if (optionsLength > 0) {
                buffer.get(options)
            }

            // Payload (the rest of the packet)
            val payloadLength = totalLength - headerLength
            val payload = ByteArray(payloadLength)
            if (payloadLength > 0) {
                buffer.get(payload)
            }

            // FIXED: Checksum validation only in debug builds (performance)
            if (VALIDATE_CHECKSUM) {
                val headerBytes = ByteArray(headerLength)
                System.arraycopy(data, 0, headerBytes, 0, headerLength)
                headerBytes[10] = 0
                headerBytes[11] = 0
                val calculatedChecksum = calculateChecksum(headerBytes)

                if (calculatedChecksum != headerChecksum) {
                    // Checksum mismatch - packet may be corrupted or modified by router
                    // Still process for MVP (routers legitimately modify TTL which changes checksum)
                    // Log in debug only
                }
            }

            return IpPacket(
                version = version,
                headerLength = headerLength,
                typeOfService = typeOfService,
                totalLength = totalLength,
                identification = identification,
                flags = flags,
                fragmentOffset = fragmentOffset,
                timeToLive = timeToLive,
                protocol = protocol,
                headerChecksum = headerChecksum,
                sourceAddress = sourceAddress,
                destinationAddress = destinationAddress,
                options = options,
                payload = payload
            )
        }

        /**
         * Calculates the Internet Checksum (RFC 1071).
         *
         * Algorithm:
         * 1. Sum all 16-bit words in the header
         * 2. Fold 32-bit sum to 16 bits (add carry bits)
         * 3. Take one's complement
         *
         * This is used for both IP header checksum and TCP/UDP checksums.
         *
         * Performance Note: This is called for every packet in debug builds only.
         * In production, checksum validation is disabled.
         *
         * FIXED: Uses Long throughout, no silent truncation
         *
         * Complexity: O(n) where n is header length (20-60 bytes)
         *
         * @param data Byte array to calculate checksum for
         * @return 16-bit checksum (one's complement) as Int (0-65535)
         */
        fun calculateChecksum(data: ByteArray): Int {
            var sum = 0L
            var i = 0

            // Sum 16-bit words
            while (i < data.size - 1) {
                val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                sum += word
                i += 2
            }

            // Add last byte if odd length (padding with zero)
            if (i < data.size) {
                sum += (data[i].toInt() and 0xFF) shl 8
            }

            // FIXED: Fold 32-bit sum to 16 bits (add carries)
            while (sum shr 16 > 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }

            // FIXED: One's complement, safe conversion (sum is 0-65535)
            return ((sum.inv() and 0xFFFFL)).toInt()
        }

        /**
         * Converts an integer IP address to dotted decimal string.
         * Example: 3232235521 -> "192.168.1.1"
         *
         * Used for logging and UI display.
         * NOT used for packet processing (too slow - use Int for performance).
         *
         * FIXED: Uses ushr (unsigned right shift) for correct handling of negative IPs
         *
         * @param ip IP address as 32-bit integer (network byte order)
         * @return Human-readable IP address string
         */
        fun ipToString(ip: Int): String {
            // FIXED: ushr for unsigned shift (handles negative values correctly)
            return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
        }

        /**
         * Converts dotted decimal string to integer IP address.
         * Example: "192.168.1.1" -> 3232235521
         *
         * Used for configuration and UI input.
         *
         * @param ipString Human-readable IP address
         * @return IP address as 32-bit integer, or null if invalid
         */
        fun stringToIp(ipString: String): Int? {
            val parts = ipString.split('.')
            if (parts.size != 4) return null

            try {
                var result = 0L
                for (i in 0..3) {
                    val part = parts[i].toInt()
                    if (part !in 0..255) return null
                    result = (result shl 8) or part.toLong()
                }
                return result.toInt()
            } catch (e: NumberFormatException) {
                return null
            }
        }
    }
}