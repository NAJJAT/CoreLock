package com.privacyguard.core.packet

import com.privacyguard.core.utils.ByteUtils

/**
 * Represents a parsed UDP datagram.
 *
 * Layout (RFC 768):
 * ```
 *  0      7 8     15 16    23 24    31
 * +--------+--------+--------+--------+
 * |     Source      |   Destination   |
 * |      Port       |      Port       |
 * +--------+--------+--------+--------+
 * |                 |                 |
 * |     Length      |    Checksum     |
 * +--------+--------+--------+--------+
 * |          data octets …            |
 * +-----------------------------------+
 * ```
 *
 * UDP is stateless and header-minimal — 8 bytes fixed, then payload.
 */
data class UdpPacket(
    /** Source port (0–65535). */
    val sourcePort: Int,
    /** Destination port (0–65535). */
    val destinationPort: Int,
    /**
     * Total length of the UDP datagram (header + data) in bytes.
     * Minimum value is 8 (header only, no data).
     */
    val length: Int,
    /**
     * UDP checksum.  A value of 0x0000 means "checksum disabled" per RFC 768.
     */
    val checksum: Int,
    /** UDP payload (application data). */
    val data: ByteArray,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Derived Properties
    // ─────────────────────────────────────────────────────────────────────────

    /** True if the checksum field is disabled (set to 0). */
    val isChecksumDisabled: Boolean get() = checksum == 0

    /** True if this datagram carries no application data. */
    val isEmpty: Boolean get() = data.isEmpty()

    /** True if the destination port is the standard DNS port (53). */
    val isDns: Boolean get() = destinationPort == PORT_DNS || sourcePort == PORT_DNS

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UdpPacket) return false
        return sourcePort      == other.sourcePort &&
               destinationPort == other.destinationPort &&
               length          == other.length &&
               data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = sourcePort
        result = 31 * result + destinationPort
        result = 31 * result + length
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String =
        "UdpPacket($sourcePort→$destinationPort len=$length dataLen=${data.size})"

    // ─────────────────────────────────────────────────────────────────────────
    // Serialization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serializes this UDP datagram to a raw byte array (8-byte header + data).
     * Checksum field is written as 0; caller should invoke
     * [com.privacyguard.core.utils.Checksum.setUdpChecksum] on the full IP+UDP packet.
     */
    fun toBytes(): ByteArray {
        val buf = ByteArray(HEADER_LENGTH + data.size)
        ByteUtils.writeUInt16(buf, 0, sourcePort)
        ByteUtils.writeUInt16(buf, 2, destinationPort)
        ByteUtils.writeUInt16(buf, 4, HEADER_LENGTH + data.size)
        ByteUtils.writeUInt16(buf, 6, 0)   // checksum = 0 (disabled); caller fixes it
        if (data.isNotEmpty()) data.copyInto(buf, HEADER_LENGTH)
        return buf
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion — Factory & Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val HEADER_LENGTH = 8

        // Well-known UDP ports
        const val PORT_DNS    = 53
        const val PORT_DHCP_C = 68
        const val PORT_DHCP_S = 67
        const val PORT_NTP    = 123
        const val PORT_QUIC   = 443   // QUIC/HTTP3 often uses 443/UDP

        /**
         * Parses a UDP datagram from [raw] bytes starting at [offset].
         * [segmentEnd] is the index one past the last byte of the UDP segment
         * (i.e. ipOffset + totalLength from the IP header).
         *
         * @return parsed [UdpPacket], or null if the data is too short or malformed.
         */
        fun parse(raw: ByteArray, offset: Int, segmentEnd: Int): UdpPacket? {
            if (segmentEnd - offset < HEADER_LENGTH) return null

            val srcPort  = ByteUtils.readUInt16(raw, offset)
            val dstPort  = ByteUtils.readUInt16(raw, offset + 2)
            val length   = ByteUtils.readUInt16(raw, offset + 4)
            val checksum = ByteUtils.readUInt16(raw, offset + 6)

            if (length < HEADER_LENGTH) return null
            // Guard against length field lying about data size
            val dataLength = minOf(length - HEADER_LENGTH, segmentEnd - offset - HEADER_LENGTH)
            val data = if (dataLength > 0)
                ByteUtils.readBytes(raw, offset + HEADER_LENGTH, dataLength)
            else
                ByteArray(0)

            return UdpPacket(
                sourcePort      = srcPort,
                destinationPort = dstPort,
                length          = length,
                checksum        = checksum,
                data            = data,
            )
        }

        /**
         * Parses a UDP datagram from the payload portion of an [IpPacket].
         */
        fun parse(ip: IpPacket): UdpPacket? {
            if (ip.protocol != IpPacket.PROTO_UDP) return null
            return parse(
                ip.rawPacket,
                ip.payloadOffset,
                ip.payloadOffset + ip.payloadLength,
            )
        }

        /**
         * Builds a UDP datagram with the given parameters.
         * Checksum is set to 0 (disabled); wrap in an IP packet and call
         * [com.privacyguard.core.utils.Checksum.setUdpChecksum] to enable it.
         */
        fun build(
            srcPort: Int,
            dstPort: Int,
            data: ByteArray = ByteArray(0),
        ): UdpPacket = UdpPacket(
            sourcePort      = srcPort,
            destinationPort = dstPort,
            length          = HEADER_LENGTH + data.size,
            checksum        = 0,
            data            = data,
        )
    }
}