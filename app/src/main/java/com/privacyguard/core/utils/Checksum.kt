package com.privacyguard.core.utils

/**
 * Internet Checksum calculator (RFC 1071).
 *
 * Computes and verifies checksums for IP, TCP, and UDP headers.
 * The Internet Checksum is a 16-bit one's complement of the one's complement
 * sum of all 16-bit words in the header (and optionally a pseudo-header).
 */
object Checksum {

    // ─────────────────────────────────────────────────────────────────────────
    // Core Checksum Algorithm
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Computes the raw one's-complement checksum sum over [buf] from [offset]
     * for [length] bytes.  The carry bits are folded until the result fits in 16 bits.
     *
     * @return the 16-bit checksum value as an Int (positive, 0x0000–0xFFFF).
     *         A correctly formed header will return 0xFFFF after summing with
     *         its own checksum field, or 0 before the complement.
     */
    fun compute(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int {
        var sum = 0L
        var i = offset
        val end = offset + length

        // Sum 16-bit words
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }

        // Handle odd byte
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }

        // Fold carries
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }

        return sum.toInt() and 0xFFFF
    }

    /**
     * Computes the final Internet Checksum (one's complement).
     * A value of 0xFFFF from [compute] becomes 0x0000 here, which is stored in
     * the packet as 0xFFFF per RFC 768 (UDP) or left as-is for TCP/IP.
     */
    fun checksum(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int {
        val raw = compute(buf, offset, length)
        return raw.inv() and 0xFFFF
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IP Header Checksum
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Computes the IPv4 header checksum.
     *
     * @param packet the raw IPv4 packet bytes.
     * @param ipOffset byte offset where the IP header starts (usually 0).
     * @return the 16-bit checksum to be written into bytes [ipOffset+10..+11].
     */
    fun ipv4HeaderChecksum(packet: ByteArray, ipOffset: Int = 0): Int {
        val ihl = (packet[ipOffset].toInt() and 0x0F) * 4   // Internet Header Length in bytes
        return checksum(packet, ipOffset, ihl)
    }

    /**
     * Verifies that the IPv4 header checksum of [packet] is correct.
     *
     * @return true if the header is intact, false if corrupted.
     */
    fun verifyIpv4Header(packet: ByteArray, ipOffset: Int = 0): Boolean {
        val ihl = (packet[ipOffset].toInt() and 0x0F) * 4
        return compute(packet, ipOffset, ihl) == 0xFFFF
    }

    /**
     * Writes the correct IPv4 header checksum into [packet] in-place.
     * Zeros out the checksum field before computing (per RFC 791).
     */
    fun setIpv4HeaderChecksum(packet: ByteArray, ipOffset: Int = 0) {
        // Zero out existing checksum field (bytes 10–11 of IP header)
        packet[ipOffset + 10] = 0
        packet[ipOffset + 11] = 0
        val cs = ipv4HeaderChecksum(packet, ipOffset)
        ByteUtils.writeUInt16(packet, ipOffset + 10, cs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TCP / UDP Pseudo-Header Checksum (RFC 793 / 768)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the 12-byte IPv4 pseudo-header used for TCP/UDP checksum calculation.
     *
     * Pseudo-header layout:
     *   0-3  : Source IP
     *   4-7  : Destination IP
     *   8    : Zero
     *   9    : Protocol (6=TCP, 17=UDP)
     *   10-11: TCP/UDP segment length (header + data)
     */
    private fun buildPseudoHeader(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        segmentLength: Int,
    ): ByteArray {
        require(srcIp.size == 4 && dstIp.size == 4) { "IPv4 addresses must be 4 bytes each" }
        val pseudo = ByteArray(12)
        srcIp.copyInto(pseudo, 0)
        dstIp.copyInto(pseudo, 4)
        pseudo[8]  = 0
        pseudo[9]  = protocol.toByte()
        ByteUtils.writeUInt16(pseudo, 10, segmentLength)
        return pseudo
    }

    /**
     * Computes the TCP checksum.
     *
     * @param packet     full raw packet (IP + TCP + data).
     * @param ipOffset   byte offset of the IP header (usually 0).
     * @return 16-bit checksum value to store in TCP header bytes [tcpOffset+16..+17].
     */
    fun tcpChecksum(packet: ByteArray, ipOffset: Int = 0): Int {
        val ihl       = (packet[ipOffset].toInt() and 0x0F) * 4
        val totalLen  = ByteUtils.readUInt16(packet, ipOffset + 2)
        val tcpLen    = totalLen - ihl
        val tcpOffset = ipOffset + ihl

        val srcIp = ByteUtils.readBytes(packet, ipOffset + 12, 4)
        val dstIp = ByteUtils.readBytes(packet, ipOffset + 16, 4)

        val pseudo = buildPseudoHeader(srcIp, dstIp, 6, tcpLen)

        // Concatenate pseudo-header + TCP segment
        val buf = pseudo + packet.copyOfRange(tcpOffset, tcpOffset + tcpLen)
        return checksum(buf)
    }

    /**
     * Writes the correct TCP checksum into [packet] in-place.
     * Zeros out the checksum field before computing.
     */
    fun setTcpChecksum(packet: ByteArray, ipOffset: Int = 0) {
        val ihl       = (packet[ipOffset].toInt() and 0x0F) * 4
        val tcpOffset = ipOffset + ihl
        // TCP checksum field is at offset 16 within TCP header
        packet[tcpOffset + 16] = 0
        packet[tcpOffset + 17] = 0
        val cs = tcpChecksum(packet, ipOffset)
        ByteUtils.writeUInt16(packet, tcpOffset + 16, cs)
    }

    /**
     * Computes the UDP checksum.
     *
     * @param packet   full raw packet (IP + UDP + data).
     * @param ipOffset byte offset of the IP header (usually 0).
     * @return 16-bit checksum (0xFFFF encodes as 0 per RFC 768 — this method
     *         returns 0xFFFF so callers can store it directly; UDP uses 0 to mean
     *         "no checksum", so 0xFFFF is the correct stored value when computed).
     */
    fun udpChecksum(packet: ByteArray, ipOffset: Int = 0): Int {
        val ihl       = (packet[ipOffset].toInt() and 0x0F) * 4
        val udpOffset = ipOffset + ihl
        val udpLen    = ByteUtils.readUInt16(packet, udpOffset + 4)

        val srcIp = ByteUtils.readBytes(packet, ipOffset + 12, 4)
        val dstIp = ByteUtils.readBytes(packet, ipOffset + 16, 4)

        val pseudo = buildPseudoHeader(srcIp, dstIp, 17, udpLen)
        val buf    = pseudo + packet.copyOfRange(udpOffset, udpOffset + udpLen)
        val cs     = checksum(buf)
        // Per RFC 768: a computed checksum of 0 must be stored as 0xFFFF
        return if (cs == 0) 0xFFFF else cs
    }

    /**
     * Writes the correct UDP checksum into [packet] in-place.
     */
    fun setUdpChecksum(packet: ByteArray, ipOffset: Int = 0) {
        val ihl       = (packet[ipOffset].toInt() and 0x0F) * 4
        val udpOffset = ipOffset + ihl
        // UDP checksum field is at offset 6 within UDP header
        packet[udpOffset + 6] = 0
        packet[udpOffset + 7] = 0
        val cs = udpChecksum(packet, ipOffset)
        ByteUtils.writeUInt16(packet, udpOffset + 6, cs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Verify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies a TCP segment's checksum.
     * @return true if checksum is valid.
     */
    fun verifyTcp(packet: ByteArray, ipOffset: Int = 0): Boolean {
        val ihl       = (packet[ipOffset].toInt() and 0x0F) * 4
        val totalLen  = ByteUtils.readUInt16(packet, ipOffset + 2)
        val tcpLen    = totalLen - ihl
        val tcpOffset = ipOffset + ihl

        val srcIp  = ByteUtils.readBytes(packet, ipOffset + 12, 4)
        val dstIp  = ByteUtils.readBytes(packet, ipOffset + 16, 4)
        val pseudo = buildPseudoHeader(srcIp, dstIp, 6, tcpLen)
        val buf    = pseudo + packet.copyOfRange(tcpOffset, tcpOffset + tcpLen)
        return compute(buf) == 0xFFFF
    }

    /**
     * Verifies a UDP segment's checksum.
     * @return true if checksum is valid (or UDP checksum is 0, meaning disabled).
     */
    fun verifyUdp(packet: ByteArray, ipOffset: Int = 0): Boolean {
        val ihl       = (packet[ipOffset].toInt() and 0x0F) * 4
        val udpOffset = ipOffset + ihl
        val storedCs  = ByteUtils.readUInt16(packet, udpOffset + 6)
        if (storedCs == 0) return true  // UDP checksum disabled

        val udpLen = ByteUtils.readUInt16(packet, udpOffset + 4)
        val srcIp  = ByteUtils.readBytes(packet, ipOffset + 12, 4)
        val dstIp  = ByteUtils.readBytes(packet, ipOffset + 16, 4)
        val pseudo = buildPseudoHeader(srcIp, dstIp, 17, udpLen)
        val buf    = pseudo + packet.copyOfRange(udpOffset, udpOffset + udpLen)
        return compute(buf) == 0xFFFF
    }
}