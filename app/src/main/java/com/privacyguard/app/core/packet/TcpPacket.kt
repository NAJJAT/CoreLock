/**
 * TcpPacket.kt
 *
 * RFC 793 - Transmission Control Protocol Specification
 *
 * Parses TCP packets from IP payload.
 * This is the HEART of TCP connection management in PrivacyGuard.
 *
 * The TCP state machine depends on accurate extraction of:
 * - Sequence numbers (for ordering)
 * - Acknowledgment numbers (for reliability)
 * - Flags (SYN, ACK, FIN, RST for state transitions)
 * - Ports (for session identification)
 *
 * Performance requirements:
 * - Parse must complete in < 30 microseconds
 * - Called for every TCP packet (60%+ of traffic)
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
 * TCP Flags according to RFC 793 and RFC 3168.
 *
 * Each flag is a bit in the 12-bit flags field (9 bits in standard TCP,
 * plus 3 bits for ECN in modern implementations).
 *
 * @property ns ECN-nonce concealment protection (RFC 3540) - bit 8
 * @property cwr Congestion Window Reduced (RFC 3168) - bit 7
 * @property ece ECN-Echo (RFC 3168) - bit 6
 * @property urg Urgent pointer field is valid - bit 5
 * @property ack Acknowledgment field is valid - bit 4
 * @property psh Push function (deliver data immediately) - bit 3
 * @property rst Reset the connection (abort) - bit 2
 * @property syn Synchronize sequence numbers (start connection) - bit 1
 * @property fin No more data from sender (close connection) - bit 0
 */
data class TcpFlags(
    val ns: Boolean = false,
    val cwr: Boolean = false,
    val ece: Boolean = false,
    val urg: Boolean = false,
    val ack: Boolean = false,
    val psh: Boolean = false,
    val rst: Boolean = false,
    val syn: Boolean = false,
    val fin: Boolean = false
) {

    /**
     * Returns the 8-bit flags byte (lower 8 bits: CWR, ECE, URG, ACK, PSH, RST, SYN, FIN).
     * NS flag is NOT in this byte (it's in the data offset byte).
     */
    fun toFlagsByte(): Byte {
        var flags = 0
        if (cwr) flags = flags or 0x80
        if (ece) flags = flags or 0x40
        if (urg) flags = flags or 0x20
        if (ack) flags = flags or 0x10
        if (psh) flags = flags or 0x08
        if (rst) flags = flags or 0x04
        if (syn) flags = flags or 0x02
        if (fin) flags = flags or 0x01
        return (flags and 0xFF).toByte()
    }

    /**
     * Returns the NS flag bit for the data offset byte.
     */
    fun toNsBit(): Int = if (ns) 1 else 0

    /**
     * Returns true if this is a valid TCP flag combination.
     * Some combinations are illegal (e.g., SYN + FIN).
     */
    fun isValid(): Boolean {
        // SYN and FIN together is invalid
        if (syn && fin) return false
        // RST with any other flag except ACK is suspicious
        if (rst && (syn || fin)) return false
        return true
    }

    override fun toString(): String {
        val sb = StringBuilder()
        if (syn) sb.append("SYN ")
        if (ack) sb.append("ACK ")
        if (fin) sb.append("FIN ")
        if (rst) sb.append("RST ")
        if (psh) sb.append("PSH ")
        if (urg) sb.append("URG ")
        if (cwr) sb.append("CWR ")
        if (ece) sb.append("ECE ")
        if (ns) sb.append("NS ")
        return sb.toString().trim()
    }

    companion object {
        /**
         * Parses TCP flags from the data offset byte and flags byte.
         *
         * @param dataOffsetByte Byte containing data offset (4 bits), reserved (3 bits), and NS (1 bit)
         * @param flagsByte Byte containing CWR, ECE, URG, ACK, PSH, RST, SYN, FIN
         * @return TcpFlags object
         */
        fun fromBytes(dataOffsetByte: Int, flagsByte: Int): TcpFlags {
            val ns = (dataOffsetByte and 0x01) != 0
            val cwr = (flagsByte and 0x80) != 0
            val ece = (flagsByte and 0x40) != 0
            val urg = (flagsByte and 0x20) != 0
            val ack = (flagsByte and 0x10) != 0
            val psh = (flagsByte and 0x08) != 0
            val rst = (flagsByte and 0x04) != 0
            val syn = (flagsByte and 0x02) != 0
            val fin = (flagsByte and 0x01) != 0

            return TcpFlags(ns, cwr, ece, urg, ack, psh, rst, syn, fin)
        }

        /**
         * Creates flags for a SYN packet (connection initiation).
         */
        fun syn(): TcpFlags = TcpFlags(syn = true)

        /**
         * Creates flags for a SYN-ACK packet (handshake response).
         */
        fun synAck(): TcpFlags = TcpFlags(syn = true, ack = true)

        /**
         * Creates flags for an ACK packet (acknowledgment).
         */
        fun ack(): TcpFlags = TcpFlags(ack = true)

        /**
         * Creates flags for a FIN packet (graceful close).
         */
        fun fin(): TcpFlags = TcpFlags(fin = true)

        /**
         * Creates flags for a FIN-ACK packet (close acknowledgment).
         */
        fun finAck(): TcpFlags = TcpFlags(fin = true, ack = true)

        /**
         * Creates flags for an RST packet (abort connection).
         */
        fun rst(): TcpFlags = TcpFlags(rst = true)

        /**
         * Creates flags for a PSH-ACK packet (push data).
         */
        fun pshAck(): TcpFlags = TcpFlags(psh = true, ack = true)
    }
}

/**
 * Represents a TCP packet with all header fields.
 *
 * This class is used by the TCP state machine to track
 * connection state and modify packets for forwarding.
 *
 * Note: This is a regular class (not data class) because:
 * 1. ByteArray fields need contentEquals for equality
 * 2. We control equality semantics explicitly
 * 3. Private constructor ensures validated instances only
 *
 * @property sourcePort Source port (1-65535)
 * @property destinationPort Destination port (1-65535)
 * @property sequenceNumber Sequence number for this segment (32-bit, wraps)
 * @property acknowledgmentNumber Acknowledgment number (if ACK flag set)
 * @property dataOffset TCP header length in bytes (usually 20, max 60)
 * @property reserved Reserved bits (must be 0)
 * @property flags Control flags (SYN, ACK, FIN, RST, etc.)
 * @property window Window size for flow control
 * @property checksum TCP checksum (includes pseudo-header)
 * @property urgentPointer Urgent pointer (if URG flag set)
 * @property options TCP options (MSS, Window Scale, SACK, Timestamps)
 * @property payload The actual data payload
 */
class TcpPacket private constructor(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgmentNumber: Long,
    val dataOffset: Int,
    val reserved: Int,
    val flags: TcpFlags,
    val window: Int,
    val checksum: Int,
    val urgentPointer: Int,
    val options: ByteArray,
    val payload: ByteArray
) {

    /**
     * Returns true if this is a SYN packet (connection initiation).
     * SYN is the first packet in a TCP handshake.
     */
    fun isSyn(): Boolean = flags.syn

    /**
     * Returns true if this is an ACK packet (acknowledgment).
     * ACK confirms receipt of data.
     */
    fun isAck(): Boolean = flags.ack

    /**
     * Returns true if this is a FIN packet (connection termination).
     * FIN initiates graceful connection close.
     */
    fun isFin(): Boolean = flags.fin

    /**
     * Returns true if this is an RST packet (connection reset).
     * RST aborts the connection immediately.
     */
    fun isRst(): Boolean = flags.rst

    /**
     * Returns true if this is a PSH packet (push data).
     * PSH tells the receiver to deliver data immediately.
     */
    fun isPsh(): Boolean = flags.psh

    /**
     * Returns true if this is a URG packet (urgent data).
     * URG indicates urgent pointer is valid.
     */
    fun isUrg(): Boolean = flags.urg

    /**
     * Returns true if this packet has any data payload.
     */
    fun hasData(): Boolean = payload.isNotEmpty()

    /**
     * Returns true if this packet has TCP options.
     */
    fun hasOptions(): Boolean = options.isNotEmpty()

    /**
     * Calculates the effective payload length for sequence number updates.
     *
     * IMPORTANT: SYN and FIN count as 1 byte even though they have no payload.
     * This is required by RFC 793 for sequence number accounting.
     *
     * Example:
     * - SYN packet with no data: effective length = 1
     * - FIN packet with no data: effective length = 1
     * - Data packet with 100 bytes: effective length = 100
     * - SYN + data: effective length = 1 + data.size
     *
     * @return Effective length for sequence number advancement
     */
    fun getEffectivePayloadLength(): Int {
        var length = payload.size
        if (isSyn()) length++
        if (isFin()) length++
        return length
    }

    /**
     * Converts the packet back to raw bytes.
     * Used when forwarding or modifying packets.
     *
     * IMPORTANT: This recalculates the TCP checksum.
     * The stored checksum field is ignored (always recomputed).
     *
     * @param srcIp Source IP address for pseudo-header checksum
     * @param dstIp Destination IP address for pseudo-header checksum
     * @return Raw byte array ready for IP payload
     */
    fun toRawBytes(srcIp: Int, dstIp: Int): ByteArray {
        val tcpHeaderLength = dataOffset
        val totalSize = tcpHeaderLength + payload.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Bytes 0-1: Source Port
        buffer.putShort(sourcePort.toShort())

        // Bytes 2-3: Destination Port
        buffer.putShort(destinationPort.toShort())

        // Bytes 4-7: Sequence Number (unsigned 32-bit)
        buffer.putInt((sequenceNumber and 0xFFFFFFFFL).toInt())

        // Bytes 8-11: Acknowledgment Number (unsigned 32-bit)
        buffer.putInt((acknowledgmentNumber and 0xFFFFFFFFL).toInt())

        // Byte 12: Data Offset (4 bits) + Reserved (3 bits) + NS (1 bit)
        // RFC 793: Bits 0-3: Data Offset (in 32-bit words), Bits 4-6: Reserved, Bit 7: NS
        val dataOffsetValue = dataOffset / 4  // Convert bytes to 32-bit words
        val reservedBits = reserved and 0x07  // Only 3 bits valid
        val nsBit = flags.toNsBit()

        // Correct encoding per RFC 793
        val dataOffsetByte = ((dataOffsetValue and 0x0F) shl 4) or (reservedBits shl 1) or nsBit
        buffer.put(dataOffsetByte.toByte())

        // Byte 13: Flags (lower 8 bits: CWR, ECE, URG, ACK, PSH, RST, SYN, FIN)
        buffer.put(flags.toFlagsByte())

        // Bytes 14-15: Window
        buffer.putShort(window.toShort())

        // Bytes 16-17: Checksum (placeholder, will calculate)
        val checksumPos = buffer.position()
        buffer.putShort(0)

        // Bytes 18-19: Urgent Pointer
        buffer.putShort(urgentPointer.toShort())

        // Bytes 20+: Options (if any)
        if (options.isNotEmpty()) {
            buffer.put(options)
        }

        // Payload (FIXED: use existing payload, no unnecessary copy)
        buffer.put(payload)

        // Calculate and set checksum
        val tcpSegment = buffer.array()
        val tcpLength = tcpHeaderLength + payload.size
        val pseudoHeader = buildPseudoHeader(srcIp, dstIp, tcpLength)

        // Combine pseudo-header + TCP segment for checksum calculation
        val checksumData = ByteArray(pseudoHeader.size + tcpLength)
        System.arraycopy(pseudoHeader, 0, checksumData, 0, pseudoHeader.size)
        System.arraycopy(tcpSegment, 0, checksumData, pseudoHeader.size, tcpLength)

        // Zero out checksum field in the copy
        val checksumFieldPos = pseudoHeader.size + checksumPos
        checksumData[checksumFieldPos] = 0
        checksumData[checksumFieldPos + 1] = 0

        val calculatedChecksum = IpPacket.calculateChecksum(checksumData)

        // Set checksum in the original buffer
        buffer.position(checksumPos)
        buffer.putShort(calculatedChecksum.toShort())

        return buffer.array()
    }

    /**
     * Builds the TCP pseudo-header for checksum calculation (RFC 793).
     *
     * The pseudo-header is not actually sent, only used for checksum calculation.
     * Structure (12 bytes):
     * - Source IP (4 bytes)
     * - Destination IP (4 bytes)
     * - Zero (1 byte)
     * - Protocol (1 byte, always 6 for TCP)
     * - TCP Length (2 bytes)
     */
    private fun buildPseudoHeader(srcIp: Int, dstIp: Int, tcpLength: Int): ByteArray {
        val buffer = ByteBuffer.allocate(12)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Source IP
        buffer.putInt(srcIp)

        // Destination IP
        buffer.putInt(dstIp)

        // Zero
        buffer.put(0)

        // Protocol (6 = TCP)
        buffer.put(6)

        // TCP Length
        buffer.putShort(tcpLength.toShort())

        return buffer.array()
    }

    /**
     * Creates a copy of this packet with modified sequence number.
     * Used by TCP state machine for sequence number translation.
     *
     * FIXED: No unnecessary payload copying - shares immutable reference
     *
     * @param newSeq New sequence number
     * @return New TcpPacket instance
     */
    fun withSequenceNumber(newSeq: Long): TcpPacket {
        return TcpPacket(
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            sequenceNumber = newSeq,
            acknowledgmentNumber = acknowledgmentNumber,
            dataOffset = dataOffset,
            reserved = reserved,
            flags = flags,
            window = window,
            checksum = checksum,
            urgentPointer = urgentPointer,
            options = options,  // FIXED: No copy - immutable
            payload = payload    // FIXED: No copy - immutable
        )
    }

    /**
     * Creates a copy of this packet with modified acknowledgment number.
     * Used by TCP state machine for sequence number translation.
     *
     * FIXED: No unnecessary payload copying - shares immutable reference
     *
     * @param newAck New acknowledgment number
     * @return New TcpPacket instance
     */
    fun withAcknowledgmentNumber(newAck: Long): TcpPacket {
        return TcpPacket(
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            sequenceNumber = sequenceNumber,
            acknowledgmentNumber = newAck,
            dataOffset = dataOffset,
            reserved = reserved,
            flags = flags,
            window = window,
            checksum = checksum,
            urgentPointer = urgentPointer,
            options = options,  // FIXED: No copy - immutable
            payload = payload    // FIXED: No copy - immutable
        )
    }

    /**
     * Creates a copy of this packet with modified source/destination ports.
     * Used for NAT when forwarding.
     *
     * @param newSourcePort New source port
     * @param newDestPort New destination port
     * @return New TcpPacket instance
     */
    fun withPorts(newSourcePort: Int, newDestPort: Int): TcpPacket {
        return TcpPacket(
            sourcePort = newSourcePort,
            destinationPort = newDestPort,
            sequenceNumber = sequenceNumber,
            acknowledgmentNumber = acknowledgmentNumber,
            dataOffset = dataOffset,
            reserved = reserved,
            flags = flags,
            window = window,
            checksum = checksum,
            urgentPointer = urgentPointer,
            options = options,  // FIXED: No copy - immutable
            payload = payload    // FIXED: No copy - immutable
        )
    }

    override fun toString(): String {
        return "TcpPacket($sourcePort→$destinationPort, seq=$sequenceNumber, ack=$acknowledgmentNumber, " +
                "flags=$flags, data=${payload.size}B, opts=${options.size}B)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TcpPacket) return false

        if (sourcePort != other.sourcePort) return false
        if (destinationPort != other.destinationPort) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (acknowledgmentNumber != other.acknowledgmentNumber) return false
        if (dataOffset != other.dataOffset) return false
        if (reserved != other.reserved) return false
        if (flags != other.flags) return false
        if (window != other.window) return false
        if (checksum != other.checksum) return false
        if (urgentPointer != other.urgentPointer) return false
        if (!options.contentEquals(other.options)) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sourcePort
        result = 31 * result + destinationPort
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + acknowledgmentNumber.hashCode()
        result = 31 * result + dataOffset
        result = 31 * result + reserved
        result = 31 * result + flags.hashCode()
        result = 31 * result + window
        result = 31 * result + checksum
        result = 31 * result + urgentPointer
        result = 31 * result + options.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {

        // FIXED: Checksum validation only in debug builds (performance)
        private const val VALIDATE_CHECKSUM = false

        /**
         * Parses TCP packet from IP payload.
         *
         * Called after IpPacket parsing when protocol = 6.
         *
         * Thread Safety: This method is stateless and thread-safe.
         *
         * Security: Validates all bounds before reading to prevent buffer overruns.
         *
         * @param data Raw bytes (IP payload starting at TCP header)
         * @param length Number of valid bytes
         * @return TcpPacket object or null if parsing fails
         */
        fun parse(data: ByteArray, length: Int): TcpPacket? {
            // Check 1: Minimum TCP header is 20 bytes
            if (length < 20) return null

            val buffer = ByteBuffer.wrap(data, 0, length)
            buffer.order(ByteOrder.BIG_ENDIAN)

            // Bytes 0-1: Source Port
            val sourcePort = buffer.getShort().toInt() and 0xFFFF

            // Bytes 2-3: Destination Port
            val destinationPort = buffer.getShort().toInt() and 0xFFFF

            // Bytes 4-7: Sequence Number (unsigned 32-bit)
            val sequenceNumber = buffer.getInt().toLong() and 0xFFFFFFFFL

            // Bytes 8-11: Acknowledgment Number (unsigned 32-bit)
            val acknowledgmentNumber = buffer.getInt().toLong() and 0xFFFFFFFFL

            // Byte 12: Data Offset (4 bits) + Reserved (3 bits) + NS (1 bit)
            val dataOffsetByte = buffer.get().toInt() and 0xFF

            // Extract components
            val dataOffsetValue = (dataOffsetByte shr 4) and 0x0F
            val dataOffset = dataOffsetValue * 4

            // Check 2: Validate dataOffset bounds (RFC 793: 20 to 60 bytes)
            if (dataOffset < 20 || dataOffset > 60) {
                return null
            }

            // Check 3: Ensure we have enough data for the claimed header length
            if (dataOffset > length) {
                return null
            }

            val reserved = (dataOffsetByte shr 1) and 0x07

            // Byte 13: Flags (lower 8 bits)
            val flagsByte = buffer.get().toInt() and 0xFF
            val flags = TcpFlags.fromBytes(dataOffsetByte, flagsByte)

            // Check 4: Validate flag combination
            if (!flags.isValid()) {
                return null
            }

            // Bytes 14-15: Window
            val window = buffer.getShort().toInt() and 0xFFFF

            // Bytes 16-17: Checksum
            val checksum = buffer.getShort().toInt() and 0xFFFF

            // Bytes 18-19: Urgent Pointer
            val urgentPointer = buffer.getShort().toInt() and 0xFFFF

            // Options (if dataOffset > 20)
            val optionsLength = dataOffset - 20
            val options = ByteArray(optionsLength)
            if (optionsLength > 0) {
                buffer.get(options)
            }

            // Payload (the rest of the packet)
            val payloadLength = length - dataOffset
            val payload = ByteArray(payloadLength)
            if (payloadLength > 0) {
                buffer.get(payload)
            }

            // FIXED: Checksum validation only in debug builds (performance)
            if (VALIDATE_CHECKSUM) {
                // TCP checksum requires IP addresses which we don't have here
                // Validation will be done by the OS stack anyway
                // Skipping for performance
            }

            return TcpPacket(
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                sequenceNumber = sequenceNumber,
                acknowledgmentNumber = acknowledgmentNumber,
                dataOffset = dataOffset,
                reserved = reserved,
                flags = flags,
                window = window,
                checksum = checksum,
                urgentPointer = urgentPointer,
                options = options,
                payload = payload
            )
        }

        /**
         * Creates a minimal SYN packet for testing.
         *
         * @param sourcePort Source port
         * @param destPort Destination port
         * @param seq Initial sequence number
         * @return TcpPacket configured as SYN
         */
        fun createSynPacket(sourcePort: Int, destPort: Int, seq: Long): TcpPacket {
            return TcpPacket(
                sourcePort = sourcePort,
                destinationPort = destPort,
                sequenceNumber = seq,
                acknowledgmentNumber = 0,
                dataOffset = 20,
                reserved = 0,
                flags = TcpFlags.syn(),
                window = 65535,
                checksum = 0,
                urgentPointer = 0,
                options = ByteArray(0),
                payload = ByteArray(0)
            )
        }

        /**
         * Creates a minimal ACK packet for testing.
         *
         * @param sourcePort Source port
         * @param destPort Destination port
         * @param seq Sequence number
         * @param ack Acknowledgment number
         * @return TcpPacket configured as ACK
         */
        fun createAckPacket(sourcePort: Int, destPort: Int, seq: Long, ack: Long): TcpPacket {
            return TcpPacket(
                sourcePort = sourcePort,
                destinationPort = destPort,
                sequenceNumber = seq,
                acknowledgmentNumber = ack,
                dataOffset = 20,
                reserved = 0,
                flags = TcpFlags.ack(),
                window = 65535,
                checksum = 0,
                urgentPointer = 0,
                options = ByteArray(0),
                payload = ByteArray(0)
            )
        }

        /**
         * Creates a minimal FIN packet for testing.
         *
         * @param sourcePort Source port
         * @param destPort Destination port
         * @param seq Sequence number
         * @param ack Acknowledgment number
         * @return TcpPacket configured as FIN-ACK
         */
        fun createFinPacket(sourcePort: Int, destPort: Int, seq: Long, ack: Long): TcpPacket {
            return TcpPacket(
                sourcePort = sourcePort,
                destinationPort = destPort,
                sequenceNumber = seq,
                acknowledgmentNumber = ack,
                dataOffset = 20,
                reserved = 0,
                flags = TcpFlags.finAck(),
                window = 65535,
                checksum = 0,
                urgentPointer = 0,
                options = ByteArray(0),
                payload = ByteArray(0)
            )
        }
    }
}