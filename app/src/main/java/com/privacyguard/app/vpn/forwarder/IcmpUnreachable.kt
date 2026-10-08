package com.privacyguard.vpn.forwarder

import com.privacyguard.core.packet.IpPacket
import com.privacyguard.core.utils.Checksum

/**
 * ICMP "destination port unreachable" replies for UDP datagrams we refuse.
 *
 * Silently dropping a datagram leaves the app waiting: a QUIC client (TikTok,
 * Snapchat, YouTube, Chrome) retries its handshake for seconds before falling
 * back to TCP. An ICMP error is delivered to the app's socket as
 * ECONNREFUSED, so it falls back at once.
 */
internal object IcmpUnreachable {

    private const val TYPE_DEST_UNREACHABLE = 3
    private const val CODE_PORT_UNREACHABLE = 3

    /** The reply to [ip], or null when [ip] is not something we should answer. */
    fun replyTo(ip: IpPacket): ByteArray? {
        if (ip.protocol != IpPacket.PROTO_UDP) return null
        val raw = ip.rawPacket
        // RFC 792: the offending IP header plus the first 8 bytes of its payload.
        val quoted = minOf(raw.size, ip.headerLength + 8)
        val total = 20 + 8 + quoted
        val pkt = ByteArray(total)
        pkt[0] = 0x45
        pkt[2] = (total ushr 8).toByte()
        pkt[3] = (total and 0xFF).toByte()
        pkt[8] = 64
        pkt[9] = IpPacket.PROTO_ICMP.toByte()
        System.arraycopy(raw, 16, pkt, 12, 4)   // from the original destination
        System.arraycopy(raw, 12, pkt, 16, 4)   // to the original source
        pkt[20] = TYPE_DEST_UNREACHABLE.toByte()
        pkt[21] = CODE_PORT_UNREACHABLE.toByte()
        System.arraycopy(raw, 0, pkt, 28, quoted)
        val icmpSum = Checksum.checksum(pkt, 20, total - 20)
        pkt[22] = (icmpSum ushr 8).toByte()
        pkt[23] = (icmpSum and 0xFF).toByte()
        Checksum.setIpv4HeaderChecksum(pkt, 0)
        return pkt
    }
}
