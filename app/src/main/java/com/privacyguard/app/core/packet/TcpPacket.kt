package com.privacyguard.core.packet

import com.privacyguard.core.utils.ByteUtils
import com.privacyguard.core.utils.Checksum

/**
 * Represents a parsed TCP segment.
 *
 * Layout (RFC 793):
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          Source Port          |       Destination Port        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        Sequence Number                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    Acknowledgment Number                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  Data |     |N|C|E|U|A|P|R|S|F|                              |
 * | Offset|Rsrvd|S|W|C|R|C|S|S|Y|I|            Window           |
 * |       |     | |R|E| |K|H|T|N|N|                              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           Checksum            |         Urgent Pointer        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    Options (if data offset > 5)               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 */
data class TcpPacket(
    /** Source port (0–65535). */
    val sourcePort: Int,
    /** Destination port (0–65535). */
    val destinationPort: Int,
    /** Sequence number (unsigned 32-bit). */
    val sequenceNumber: Long,
    /** Acknowledgment number (unsigned 32-bit). */
    val acknowledgmentNumber: Long,
    /** Data offset: TCP header length in bytes (data offset × 4). */
    val headerLength: Int,
    // ── Control Flags ───────────────────────────────────────────────────────
    val flagNs:  Boolean,  // ECN-nonce
    val flagCwr: Boolean,  // Congestion Window Reduced
    val flagEce: Boolean,  // ECN-Echo
    val flagUrg: Boolean,  // Urgent
    val flagAck: Boolean,  // Acknowledgment
    val flagPsh: Boolean,  // Push
    val flagRst: Boolean,  // Reset
    val flagSyn: Boolean,  // Synchronize
    val flagFin: Boolean,  // Finish
    /** Receive window size. */
    val windowSize: Int,
    /** TCP checksum. */
    val checksum: Int,
    /** Urgent pointer (valid only when URG is set). */
    val urgentPointer: Int,
    /** Raw TCP options bytes (may be empty). */
    val options: ByteArray,
    /** TCP payload (application data). */
    val data: ByteArray,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Derived Properties
    // ─────────────────────────────────────────────────────────────────────────

    /** Compact flags string, e.g. "[SYN ACK]". */
    val flagsString: String get() = buildString {
        append('[')
        if (flagSyn) append("SYN ")
        if (flagAck) append("ACK ")
        if (flagFin) append("FIN ")
        if (flagRst) append("RST ")
        if (flagPsh) append("PSH ")
        if (flagUrg) append("URG ")
        if (flagCwr) append("CWR ")
        if (flagEce) append("ECE ")
        if (flagNs)  append("NS ")
        val result = toString()
        if (result == "[") return "[]"
        append(']')
    }.trimEnd()

    /** True if this is a SYN packet (connection initiation). */
    val isSyn: Boolean get() = flagSyn && !flagAck

    /** True if this is a SYN-ACK packet (connection accepted). */
    val isSynAck: Boolean get() = flagSyn && flagAck

    /** True if this is a FIN packet (connection teardown). */
    val isFin: Boolean get() = flagFin

    /** True if this is a RST packet (connection reset). */
    val isRst: Boolean get() = flagRst

    /** True if this segment carries application data. */
    val hasData: Boolean get() = data.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TcpPacket) return false
        return sourcePort == other.sourcePort &&
               destinationPort == other.destinationPort &&
               sequenceNumber == other.sequenceNumber &&
               acknowledgmentNumber == other.acknowledgmentNumber &&
               flagSyn == other.flagSyn && flagAck == other.flagAck &&
               flagFin == other.flagFin && flagRst == other.flagRst &&
               data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = sourcePort
        result = 31 * result + destinationPort
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + acknowledgmentNumber.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String =
        "TcpPacket($sourcePort→$destinationPort $flagsString seq=$sequenceNumber ack=$acknowledgmentNumber " +
        "win=$windowSize dataLen=${data.size})"

    // ─────────────────────────────────────────────────────────────────────────
    // Serialization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serializes this TCP segment back to a raw byte array (TCP header + data).
     * Does NOT include the IP header — use [IpPacket.build] to wrap it.
     * Checksum field is set to 0; caller must invoke [Checksum.setTcpChecksum]
     * on the full IP+TCP packet.
     */
    fun toBytes(): ByteArray {
        val buf = ByteArray(headerLength + data.size)
        ByteUtils.writeUInt16(buf, 0, sourcePort)
        ByteUtils.writeUInt16(buf, 2, destinationPort)
        ByteUtils.writeUInt32(buf, 4, sequenceNumber)
        ByteUtils.writeUInt32(buf, 8, acknowledgmentNumber)

        val dataOffset = (headerLength / 4) shl 4
        val flags = dataOffset or
                (if (flagNs)  0x0100 else 0) or
                (if (flagCwr) 0x0080 else 0) or
                (if (flagEce) 0x0040 else 0) or
                (if (flagUrg) 0x0020 else 0) or
                (if (flagAck) 0x0010 else 0) or
                (if (flagPsh) 0x0008 else 0) or
                (if (flagRst) 0x0004 else 0) or
                (if (flagSyn) 0x0002 else 0) or
                (if (flagFin) 0x0001 else 0)
        ByteUtils.writeUInt16(buf, 12, flags)
        ByteUtils.writeUInt16(buf, 14, windowSize)
        // Checksum at [16] left as 0 — caller sets it
        ByteUtils.writeUInt16(buf, 18, urgentPointer)

        if (options.isNotEmpty()) options.copyInto(buf, 20)
        if (data.isNotEmpty())    data.copyInto(buf, headerLength)
        return buf
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion — Factory & Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        private const val MIN_TCP_HEADER = 20

        // Well-known ports
        const val PORT_HTTP  = 80
        const val PORT_HTTPS = 443
        const val PORT_DNS   = 53
        const val PORT_FTP   = 21
        const val PORT_SSH   = 22

        /**
         * Parses a TCP segment from [raw] bytes starting at [offset].
         * [payloadEnd] marks the last byte of the TCP segment (totalLength - ipHeaderLength).
         *
         * @return parsed [TcpPacket] or null if data is malformed.
         */
        fun parse(raw: ByteArray, offset: Int, payloadEnd: Int): TcpPacket? {
            if (payloadEnd - offset < MIN_TCP_HEADER) return null

            val srcPort  = ByteUtils.readUInt16(raw, offset)
            val dstPort  = ByteUtils.readUInt16(raw, offset + 2)
            val seqNum   = ByteUtils.readUInt32(raw, offset + 4)
            val ackNum   = ByteUtils.readUInt32(raw, offset + 8)

            val dataOffsetByte = ByteUtils.readUInt16(raw, offset + 12)
            val headerLen = ((dataOffsetByte ushr 12) and 0xF) * 4

            if (headerLen < MIN_TCP_HEADER || offset + headerLen > payloadEnd) return null

            val flagsWord = dataOffsetByte and 0x01FF
            val flagNs    = (flagsWord and 0x0100) != 0
            val flagCwr   = (flagsWord and 0x0080) != 0
            val flagEce   = (flagsWord and 0x0040) != 0
            val flagUrg   = (flagsWord and 0x0020) != 0
            val flagAck   = (flagsWord and 0x0010) != 0
            val flagPsh   = (flagsWord and 0x0008) != 0
            val flagRst   = (flagsWord and 0x0004) != 0
            val flagSyn   = (flagsWord and 0x0002) != 0
            val flagFin   = (flagsWord and 0x0001) != 0

            val window        = ByteUtils.readUInt16(raw, offset + 14)
            val checksum      = ByteUtils.readUInt16(raw, offset + 16)
            val urgentPointer = ByteUtils.readUInt16(raw, offset + 18)

            val options = if (headerLen > MIN_TCP_HEADER)
                ByteUtils.readBytes(raw, offset + MIN_TCP_HEADER, headerLen - MIN_TCP_HEADER)
            else
                ByteArray(0)

            val dataStart  = offset + headerLen
            val dataLength = payloadEnd - dataStart
            val data = if (dataLength > 0)
                ByteUtils.readBytes(raw, dataStart, dataLength)
            else
                ByteArray(0)

            return TcpPacket(
                sourcePort            = srcPort,
                destinationPort       = dstPort,
                sequenceNumber        = seqNum,
                acknowledgmentNumber  = ackNum,
                headerLength          = headerLen,
                flagNs                = flagNs,
                flagCwr               = flagCwr,
                flagEce               = flagEce,
                flagUrg               = flagUrg,
                flagAck               = flagAck,
                flagPsh               = flagPsh,
                flagRst               = flagRst,
                flagSyn               = flagSyn,
                flagFin               = flagFin,
                windowSize            = window,
                checksum              = checksum,
                urgentPointer         = urgentPointer,
                options               = options,
                data                  = data,
            )
        }

        /**
         * Parses a TCP segment directly from the payload portion of an [IpPacket].
         */
        fun parse(ip: IpPacket): TcpPacket? {
            if (ip.protocol != IpPacket.PROTO_TCP) return null
            return parse(ip.rawPacket, ip.payloadOffset, ip.payloadOffset + ip.payloadLength)
        }

        /**
         * Builds a minimal TCP SYN segment.
         */
        fun buildSyn(srcPort: Int, dstPort: Int, seqNum: Long, windowSize: Int = 65535): TcpPacket =
            TcpPacket(
                sourcePort            = srcPort,
                destinationPort       = dstPort,
                sequenceNumber        = seqNum,
                acknowledgmentNumber  = 0L,
                headerLength          = MIN_TCP_HEADER,
                flagNs = false, flagCwr = false, flagEce = false, flagUrg = false,
                flagAck = false, flagPsh = false, flagRst = false,
                flagSyn = true, flagFin = false,
                windowSize            = windowSize,
                checksum              = 0,
                urgentPointer         = 0,
                options               = ByteArray(0),
                data                  = ByteArray(0),
            )

        /**
         * Builds a TCP RST segment for rejecting a connection.
         */
        fun buildRst(srcPort: Int, dstPort: Int, seqNum: Long): TcpPacket =
            TcpPacket(
                sourcePort            = srcPort,
                destinationPort       = dstPort,
                sequenceNumber        = seqNum,
                acknowledgmentNumber  = 0L,
                headerLength          = MIN_TCP_HEADER,
                flagNs = false, flagCwr = false, flagEce = false, flagUrg = false,
                flagAck = false, flagPsh = false, flagRst = true,
                flagSyn = false, flagFin = false,
                windowSize            = 0,
                checksum              = 0,
                urgentPointer         = 0,
                options               = ByteArray(0),
                data                  = ByteArray(0),
            )
    }
}