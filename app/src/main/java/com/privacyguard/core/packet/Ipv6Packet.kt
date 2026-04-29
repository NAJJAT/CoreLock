package com.privacyguard.core.packet

/**
 * Parsed IPv6 packet header (RFC 8200).
 *
 * Fixed 40-byte header:
 *   0–3   Version(4) | Traffic Class(8) | Flow Label(20)
 *   4–5   Payload Length
 *   6     Next Header (protocol: 6=TCP, 17=UDP, 58=ICMPv6, 59=No Next Header)
 *   7     Hop Limit
 *   8–23  Source Address (128 bits)
 *   24–39 Destination Address (128 bits)
 *   40+   Payload
 *
 * This implementation handles only the fixed header. Extension headers are
 * treated as payload (tunnelling support handles hop-by-hop / routing later).
 */
data class Ipv6Packet(
    val version: Int,
    val trafficClass: Int,
    val flowLabel: Int,
    val payloadLength: Int,
    /** Next Header value — 6=TCP, 17=UDP, 58=ICMPv6. */
    val nextHeader: Int,
    val hopLimit: Int,
    val sourceIp: String,
    val destinationIp: String,
    val rawPacket: ByteArray,
) {
    companion object {
        const val HEADER_LEN = 40
        const val PROTO_TCP   = 6
        const val PROTO_UDP   = 17
        const val PROTO_ICMP6 = 58

        fun parse(raw: ByteArray): Ipv6Packet? {
            if (raw.size < HEADER_LEN) return null
            if ((raw[0].toInt() ushr 4) and 0xF != 6) return null

            val trafficClass = ((raw[0].toInt() and 0x0F) shl 4) or ((raw[1].toInt() ushr 4) and 0x0F)
            val flowLabel    = ((raw[1].toInt() and 0x0F) shl 16) or
                               ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
            val payloadLen   = ((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)
            val nextHeader   = raw[6].toInt() and 0xFF
            val hopLimit     = raw[7].toInt() and 0xFF
            val srcIp        = parseAddress(raw, 8)
            val dstIp        = parseAddress(raw, 24)

            return Ipv6Packet(6, trafficClass, flowLabel, payloadLen, nextHeader, hopLimit, srcIp, dstIp, raw)
        }

        /** Parse 16 bytes at [offset] as a compressed IPv6 address string. */
        fun parseAddress(raw: ByteArray, offset: Int): String {
            if (raw.size < offset + 16) return "::"
            val groups = (0 until 8).map { i ->
                ((raw[offset + i * 2].toInt() and 0xFF) shl 8) or (raw[offset + i * 2 + 1].toInt() and 0xFF)
            }
            // RFC 5952 compression: find longest run of consecutive zero groups
            var bestStart = -1; var bestLen = 0
            var cur = -1; var curLen = 0
            for (i in 0..7) {
                if (groups[i] == 0) {
                    if (cur < 0) { cur = i; curLen = 1 } else curLen++
                    if (curLen > bestLen) { bestStart = cur; bestLen = curLen }
                } else { cur = -1; curLen = 0 }
            }
            return buildString {
                var i = 0
                while (i <= 7) {
                    if (i == bestStart && bestLen > 1) {
                        append("::")
                        i += bestLen
                    } else {
                        if (i > 0 && (i != bestStart + bestLen)) append(":")
                        append(groups[i].toString(16))
                        i++
                    }
                }
            }
        }

        /** Write a 16-byte IPv6 address into [dst] at [offset]. */
        fun writeAddress(dst: ByteArray, offset: Int, addr: String) {
            val groups = expandIpv6(addr)
            for (i in 0 until 8) {
                dst[offset + i * 2]     = (groups[i] ushr 8).toByte()
                dst[offset + i * 2 + 1] = (groups[i] and 0xFF).toByte()
            }
        }

        /** Expand a compressed IPv6 string to 8 groups of Int. */
        fun expandIpv6(addr: String): IntArray {
            val parts = addr.split("::", limit = 2)
            if (parts.size == 1) {
                val groups = addr.split(":")
                return IntArray(8) { i -> groups.getOrNull(i)?.toInt(16) ?: 0 }
            }
            val left  = if (parts[0].isEmpty()) emptyList() else parts[0].split(":").map { it.toInt(16) }
            val right = if (parts[1].isEmpty()) emptyList() else parts[1].split(":").map { it.toInt(16) }
            val zeros = 8 - left.size - right.size
            val all   = left + List(zeros) { 0 } + right
            return IntArray(8) { i -> all.getOrElse(i) { 0 } }
        }

        /**
         * Build a complete IPv6 + TCP packet.
         * [srcAddr] / [dstAddr] must be full IPv6 address strings.
         */
        fun buildTcp(
            srcAddr: String, srcPort: Int,
            dstAddr: String, dstPort: Int,
            seqNum: Long, ackNum: Long,
            syn: Boolean = false, ack: Boolean = false,
            psh: Boolean = false, fin: Boolean = false, rst: Boolean = false,
            data: ByteArray = ByteArray(0),
        ): ByteArray {
            val tcpLen   = 20 + data.size
            val totalLen = HEADER_LEN + tcpLen
            val pkt      = ByteArray(totalLen)

            // IPv6 header
            pkt[0] = 0x60.toByte()
            pkt[4] = (tcpLen ushr 8).toByte()
            pkt[5] = (tcpLen and 0xFF).toByte()
            pkt[6] = PROTO_TCP.toByte()
            pkt[7] = 64 // hop limit
            writeAddress(pkt, 8, srcAddr)
            writeAddress(pkt, 24, dstAddr)

            // TCP header
            val t = HEADER_LEN
            pkt[t]     = (srcPort ushr 8).toByte()
            pkt[t + 1] = (srcPort and 0xFF).toByte()
            pkt[t + 2] = (dstPort ushr 8).toByte()
            pkt[t + 3] = (dstPort and 0xFF).toByte()
            writeLong(pkt, t + 4,  seqNum)
            writeLong(pkt, t + 8,  ackNum)
            pkt[t + 12] = 0x50.toByte()  // data offset = 5 words
            pkt[t + 13] = (
                (if (syn) 0x02 else 0) or (if (ack) 0x10 else 0) or
                (if (psh) 0x08 else 0) or (if (fin) 0x01 else 0) or
                (if (rst) 0x04 else 0)
            ).toByte()
            pkt[t + 14] = 0xFF.toByte()
            pkt[t + 15] = 0xFF.toByte()

            if (data.isNotEmpty()) data.copyInto(pkt, t + 20)

            setTcpChecksumIpv6(pkt, srcAddr, dstAddr, t, tcpLen)
            return pkt
        }

        /**
         * Build a complete IPv6 + UDP packet.
         */
        fun buildUdp(
            srcAddr: String, srcPort: Int,
            dstAddr: String, dstPort: Int,
            data: ByteArray,
        ): ByteArray {
            val udpLen   = 8 + data.size
            val totalLen = HEADER_LEN + udpLen
            val pkt      = ByteArray(totalLen)

            pkt[0] = 0x60.toByte()
            pkt[4] = (udpLen ushr 8).toByte()
            pkt[5] = (udpLen and 0xFF).toByte()
            pkt[6] = PROTO_UDP.toByte()
            pkt[7] = 64
            writeAddress(pkt, 8, srcAddr)
            writeAddress(pkt, 24, dstAddr)

            val u = HEADER_LEN
            pkt[u]     = (srcPort ushr 8).toByte()
            pkt[u + 1] = (srcPort and 0xFF).toByte()
            pkt[u + 2] = (dstPort ushr 8).toByte()
            pkt[u + 3] = (dstPort and 0xFF).toByte()
            pkt[u + 4] = (udpLen ushr 8).toByte()
            pkt[u + 5] = (udpLen and 0xFF).toByte()
            if (data.isNotEmpty()) data.copyInto(pkt, u + 8)

            setUdpChecksumIpv6(pkt, srcAddr, dstAddr, u, udpLen)
            return pkt
        }

        // ── Checksum helpers ──────────────────────────────────────────────────

        private fun writeLong(dst: ByteArray, off: Int, v: Long) {
            dst[off]     = ((v ushr 24) and 0xFF).toByte()
            dst[off + 1] = ((v ushr 16) and 0xFF).toByte()
            dst[off + 2] = ((v ushr 8)  and 0xFF).toByte()
            dst[off + 3] = ( v          and 0xFF).toByte()
        }

        /** IPv6 pseudo-header checksum for TCP: src(16)+dst(16)+length(4)+zeros(3)+nextHeader(1). */
        private fun pseudoHeaderSum(src: String, dst: String, length: Int, proto: Int): Long {
            val ph = ByteArray(40)
            writeAddress(ph, 0, src)
            writeAddress(ph, 16, dst)
            ph[32] = (length ushr 24).toByte()
            ph[33] = (length ushr 16 and 0xFF).toByte()
            ph[34] = (length ushr 8  and 0xFF).toByte()
            ph[35] = (length         and 0xFF).toByte()
            ph[39] = proto.toByte()
            return onesComplementSum(ph, 0, ph.size)
        }

        private fun onesComplementSum(data: ByteArray, offset: Int, length: Int): Long {
            var sum = 0L
            var i = offset
            while (i + 1 < offset + length) {
                sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
            }
            if ((length and 1) != 0) sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
            while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
            return sum
        }

        private fun setTcpChecksumIpv6(pkt: ByteArray, src: String, dst: String, tcpOffset: Int, tcpLen: Int) {
            pkt[tcpOffset + 16] = 0
            pkt[tcpOffset + 17] = 0
            var sum = pseudoHeaderSum(src, dst, tcpLen, PROTO_TCP)
            sum += onesComplementSum(pkt, tcpOffset, tcpLen)
            while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
            val checksum = sum.inv() and 0xFFFF
            pkt[tcpOffset + 16] = (checksum ushr 8).toByte()
            pkt[tcpOffset + 17] = (checksum and 0xFF).toByte()
        }

        private fun setUdpChecksumIpv6(pkt: ByteArray, src: String, dst: String, udpOffset: Int, udpLen: Int) {
            pkt[udpOffset + 6] = 0
            pkt[udpOffset + 7] = 0
            var sum = pseudoHeaderSum(src, dst, udpLen, PROTO_UDP)
            sum += onesComplementSum(pkt, udpOffset, udpLen)
            while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
            val checksum = sum.inv() and 0xFFFF
            pkt[udpOffset + 6] = (checksum ushr 8).toByte()
            pkt[udpOffset + 7] = (checksum and 0xFF).toByte()
        }
    }
}
