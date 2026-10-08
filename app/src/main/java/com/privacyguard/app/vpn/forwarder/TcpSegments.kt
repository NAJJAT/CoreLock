package com.privacyguard.vpn.forwarder

import com.privacyguard.core.utils.Checksum

/**
 * TCP sequence arithmetic and IPv4/TCP segment building for [TcpForwarder].
 *
 * Sequence numbers are unsigned 32-bit and wrap: a connection that starts near
 * 2^32 crosses zero after a few hundred KB. Plain Long comparison breaks there
 * (the old forwarder then stopped ACKing and the connection hung), so every
 * comparison goes through [diff], and every stored value through [add].
 */
internal object TcpSegments {

    private const val MASK = 0xFFFFFFFFL

    /** Our MSS, sent in the SYN-ACK: TUN MTU 1500 − 20 IP − 20 TCP. */
    const val MSS = 1460

    /** `a + n` modulo 2^32. */
    fun add(a: Long, n: Long): Long = (a + n) and MASK

    /** Signed distance `a − b` in sequence space (positive when a is after b). */
    fun diff(a: Long, b: Long): Long = ((a - b) shl 32) shr 32

    /**
     * The window-scale shift from a SYN's options (RFC 7323), or -1 when the
     * option is absent or the options are malformed.
     */
    fun windowShift(options: ByteArray): Int {
        var i = 0
        while (i < options.size) {
            when (val kind = options[i].toInt() and 0xFF) {
                0 -> return -1                       // end of options
                1 -> i++                             // NOP
                else -> {
                    if (i + 1 >= options.size) return -1
                    val len = options[i + 1].toInt() and 0xFF
                    if (len < 2 || i + len > options.size) return -1
                    if (kind == 3 && len == 3) return minOf(options[i + 2].toInt() and 0xFF, 14)
                    i += len
                }
            }
        }
        return -1
    }

    /**
     * Builds an IPv4 TCP segment with checksums set. A SYN gets an MSS option and,
     * when [windowScale] is true, a window-scale option with shift 0: that lets the
     * device scale its own window past 64 KB while ours stays unscaled.
     */
    @Suppress("LongParameterList")
    fun build(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int = 65_535,
        windowScale: Boolean = false,
        data: ByteArray = EMPTY,
        dataOffset: Int = 0,
        dataLength: Int = data.size - dataOffset,
    ): ByteArray {
        val syn = flags and SYN != 0
        val optLen = when {
            !syn -> 0
            windowScale -> 8     // MSS (4) + NOP (1) + WS (3)
            else -> 4            // MSS (4)
        }
        val tcpHeader = 20 + optLen
        val totalLen = 20 + tcpHeader + dataLength
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x45
        pkt[2] = (totalLen ushr 8).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        pkt[6] = 0x40              // Don't Fragment
        pkt[8] = 64
        pkt[9] = 6
        writeIpv4(pkt, 12, srcIp)
        writeIpv4(pkt, 16, dstIp)

        pkt[20] = (srcPort ushr 8).toByte()
        pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = (dstPort ushr 8).toByte()
        pkt[23] = (dstPort and 0xFF).toByte()
        writeUInt32(pkt, 24, seq)
        writeUInt32(pkt, 28, ack)
        pkt[32] = ((tcpHeader / 4) shl 4).toByte()
        pkt[33] = flags.toByte()
        val win = window.coerceIn(0, 65_535)
        pkt[34] = (win ushr 8).toByte()
        pkt[35] = (win and 0xFF).toByte()

        if (syn) {
            pkt[40] = 2; pkt[41] = 4
            pkt[42] = (MSS ushr 8).toByte(); pkt[43] = (MSS and 0xFF).toByte()
            if (windowScale) {
                pkt[44] = 1                           // NOP
                pkt[45] = 3; pkt[46] = 3; pkt[47] = 0 // WS, shift 0
            }
        }
        if (dataLength > 0) System.arraycopy(data, dataOffset, pkt, 20 + tcpHeader, dataLength)
        Checksum.setIpv4HeaderChecksum(pkt, 0)
        Checksum.setTcpChecksum(pkt, 0)
        return pkt
    }

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    private val EMPTY = ByteArray(0)

    private fun writeUInt32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    private fun writeIpv4(buf: ByteArray, off: Int, ip: String) {
        var part = 0
        var value = 0
        for (c in ip) {
            if (c == '.') {
                buf[off + part++] = value.toByte()
                value = 0
            } else {
                value = value * 10 + (c - '0')
            }
        }
        buf[off + part] = value.toByte()
    }
}
