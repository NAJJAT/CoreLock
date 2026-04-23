package com.privacyguard.core.packet

import com.privacyguard.core.utils.ByteUtils
import com.privacyguard.core.utils.Checksum

/**
 * Represents a parsed IPv4 packet.
 *
 * Layout (RFC 791):
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |Version|  IHL  |Type of Service|          Total Length         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |         Identification        |Flags|      Fragment Offset    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  Time to Live |    Protocol   |         Header Checksum       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       Source Address                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    Destination Address                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    Options (if IHL > 5)                       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 */
data class IpPacket(
    /** IP version — always 4 for IPv4. */
    val version: Int,
    /** Internet Header Length in bytes (IHL field × 4). */
    val headerLength: Int,
    /** Differentiated Services / Type of Service byte. */
    val dscp: Int,
    /** Total packet length including header and data. */
    val totalLength: Int,
    /** Identification field for fragmentation reassembly. */
    val identification: Int,
    /** Don't Fragment (DF) flag. */
    val flagDf: Boolean,
    /** More Fragments (MF) flag. */
    val flagMf: Boolean,
    /** Fragment offset in 8-byte units. */
    val fragmentOffset: Int,
    /** Time to Live. */
    val ttl: Int,
    /** Layer-4 protocol number (6=TCP, 17=UDP, 1=ICMP, …). */
    val protocol: Int,
    /** Header checksum. */
    val headerChecksum: Int,
    /** Source IP address in dotted-decimal notation. */
    val sourceIp: String,
    /** Destination IP address in dotted-decimal notation. */
    val destinationIp: String,
    /** Raw options bytes (empty if IHL == 5). */
    val options: ByteArray,
    /** The full original raw packet bytes (header + payload). */
    val rawPacket: ByteArray,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Derived Properties
    // ─────────────────────────────────────────────────────────────────────────

    /** True if this packet is not fragmented (DF set and fragment offset == 0). */
    val isUnfragmented: Boolean get() = !flagMf && fragmentOffset == 0

    /** Byte offset within [rawPacket] where the transport-layer payload starts. */
    val payloadOffset: Int get() = headerLength

    /** Length of the transport-layer payload in bytes. */
    val payloadLength: Int get() = totalLength - headerLength

    /** Returns a copy of the payload (transport-layer header + data). */
    val payload: ByteArray get() = rawPacket.copyOfRange(payloadOffset, payloadOffset + payloadLength)

    /** Human-readable protocol name. */
    val protocolName: String get() = when (protocol) {
        PROTO_ICMP -> "ICMP"
        PROTO_TCP  -> "TCP"
        PROTO_UDP  -> "UDP"
        else       -> "PROTO($protocol)"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Equality / HashCode (exclude mutable byte arrays from auto-gen)
    // ─────────────────────────────────────────────────────────────────────────

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IpPacket) return false
        return version == other.version &&
               headerLength == other.headerLength &&
               totalLength == other.totalLength &&
               identification == other.identification &&
               protocol == other.protocol &&
               sourceIp == other.sourceIp &&
               destinationIp == other.destinationIp &&
               rawPacket.contentEquals(other.rawPacket)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + headerLength
        result = 31 * result + totalLength
        result = 31 * result + identification
        result = 31 * result + protocol
        result = 31 * result + sourceIp.hashCode()
        result = 31 * result + destinationIp.hashCode()
        result = 31 * result + rawPacket.contentHashCode()
        return result
    }

    override fun toString(): String =
        "IpPacket(v$version $protocolName $sourceIp→$destinationIp " +
        "len=$totalLength ttl=$ttl id=0x${identification.toString(16).uppercase()})"

    // ─────────────────────────────────────────────────────────────────────────
    // Mutation helpers (returns new packet bytes)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a new raw byte array with [ttl] decremented by 1
     * and the header checksum recalculated.
     * Useful when forwarding packets (acting as a router).
     */
    fun withDecrementedTtl(): ByteArray {
        val out = rawPacket.copyOf()
        out[8] = (ttl - 1).coerceAtLeast(0).toByte()
        Checksum.setIpv4HeaderChecksum(out, 0)
        return out
    }

    /**
     * Returns a new raw byte array with source IP replaced by [newSrcIp]
     * and both IP header and transport-layer checksums recalculated.
     */
    fun withSourceIp(newSrcIp: String): ByteArray {
        val out = rawPacket.copyOf()
        ByteUtils.writeIpv4Address(out, 12, newSrcIp)
        Checksum.setIpv4HeaderChecksum(out, 0)
        when (protocol) {
            PROTO_TCP -> Checksum.setTcpChecksum(out, 0)
            PROTO_UDP -> Checksum.setUdpChecksum(out, 0)
        }
        return out
    }

    /**
     * Returns a new raw byte array with destination IP replaced by [newDstIp]
     * and checksums recalculated.
     */
    fun withDestinationIp(newDstIp: String): ByteArray {
        val out = rawPacket.copyOf()
        ByteUtils.writeIpv4Address(out, 16, newDstIp)
        Checksum.setIpv4HeaderChecksum(out, 0)
        when (protocol) {
            PROTO_TCP -> Checksum.setTcpChecksum(out, 0)
            PROTO_UDP -> Checksum.setUdpChecksum(out, 0)
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion — Factory & Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val PROTO_ICMP = 1
        const val PROTO_TCP  = 6
        const val PROTO_UDP  = 17

        private const val MIN_IP_HEADER = 20   // 5 × 4 bytes
        private const val MIN_PACKET    = 20

        /**
         * Parses a raw byte array into an [IpPacket].
         *
         * @param raw    the raw bytes starting at the IPv4 header (offset 0).
         * @param offset byte offset where the IP header begins (default 0).
         * @return       a parsed [IpPacket], or null if the data is malformed.
         */
        fun parse(raw: ByteArray, offset: Int = 0): IpPacket? {
            if (raw.size - offset < MIN_PACKET) return null

            val firstByte  = ByteUtils.readUInt8(raw, offset)
            val version    = firstByte ushr 4
            val ihl        = (firstByte and 0x0F) * 4

            if (version != 4) return null
            if (ihl < MIN_IP_HEADER) return null
            if (raw.size - offset < ihl) return null

            val dscp        = ByteUtils.readUInt8(raw, offset + 1)
            val totalLength = ByteUtils.readUInt16(raw, offset + 2)
            if (raw.size - offset < totalLength) return null

            val identification = ByteUtils.readUInt16(raw, offset + 4)

            val flagsFragment  = ByteUtils.readUInt16(raw, offset + 6)
            val flagDf         = (flagsFragment and 0x4000) != 0
            val flagMf         = (flagsFragment and 0x2000) != 0
            val fragmentOffset = (flagsFragment and 0x1FFF) * 8

            val ttl            = ByteUtils.readUInt8(raw, offset + 8)
            val protocol       = ByteUtils.readUInt8(raw, offset + 9)
            val headerChecksum = ByteUtils.readUInt16(raw, offset + 10)
            val sourceIp       = ByteUtils.readIpv4Address(raw, offset + 12)
            val destinationIp  = ByteUtils.readIpv4Address(raw, offset + 16)

            val options = if (ihl > MIN_IP_HEADER)
                ByteUtils.readBytes(raw, offset + MIN_IP_HEADER, ihl - MIN_IP_HEADER)
            else
                ByteArray(0)

            // Take only [totalLength] bytes from [offset] as the canonical packet
            val rawPacket = raw.copyOfRange(offset, offset + totalLength)

            return IpPacket(
                version        = version,
                headerLength   = ihl,
                dscp           = dscp,
                totalLength    = totalLength,
                identification = identification,
                flagDf         = flagDf,
                flagMf         = flagMf,
                fragmentOffset = fragmentOffset,
                ttl            = ttl,
                protocol       = protocol,
                headerChecksum = headerChecksum,
                sourceIp       = sourceIp,
                destinationIp  = destinationIp,
                options        = options,
                rawPacket      = rawPacket,
            )
        }

        /**
         * Builds a minimal IPv4 header byte array.
         *
         * @param protocol    layer-4 protocol number.
         * @param srcIp       source IP (dotted-decimal).
         * @param dstIp       destination IP (dotted-decimal).
         * @param payload     layer-4 header + data.
         * @param ttl         Time to Live (default 64).
         * @param ident       identification field (default 0).
         * @param df          Don't Fragment flag (default false).
         * @return raw packet bytes with correct checksum.
         */
        fun build(
            protocol: Int,
            srcIp: String,
            dstIp: String,
            payload: ByteArray,
            ttl: Int = 64,
            ident: Int = 0,
            df: Boolean = false,
        ): ByteArray {
            val ihl      = MIN_IP_HEADER
            val totalLen = ihl + payload.size
            val packet   = ByteArray(totalLen)

            packet[0] = ((4 shl 4) or (ihl / 4)).toByte()           // Version + IHL
            packet[1] = 0                                             // DSCP/ECN
            ByteUtils.writeUInt16(packet, 2, totalLen)                // Total Length
            ByteUtils.writeUInt16(packet, 4, ident)                   // Identification
            val flags = if (df) 0x4000 else 0
            ByteUtils.writeUInt16(packet, 6, flags)                   // Flags + Frag Offset
            packet[8] = ttl.toByte()                                  // TTL
            packet[9] = protocol.toByte()                             // Protocol
            // Checksum filled after header complete
            ByteUtils.writeIpv4Address(packet, 12, srcIp)             // Source IP
            ByteUtils.writeIpv4Address(packet, 16, dstIp)             // Destination IP
            payload.copyInto(packet, ihl)                             // Payload

            Checksum.setIpv4HeaderChecksum(packet, 0)
            return packet
        }
    }
}