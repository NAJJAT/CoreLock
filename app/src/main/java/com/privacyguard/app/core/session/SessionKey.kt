package com.privacyguard.core.session

/**
 * Immutable key that uniquely identifies a network connection (session).
 *
 * A session is identified by the 4-tuple:
 *   (sourceIp, sourcePort, destinationIp, destinationPort, protocol)
 *
 * This is used as the key in [SessionTable] to map virtual TUN connections
 * to their corresponding real sockets opened on behalf of the device.
 *
 * Equality and hashCode are based on all five fields, enabling safe use
 * as HashMap keys without collision between TCP and UDP sessions on the
 * same port pair.
 */
data class SessionKey(
    /** Source IP address (device-side) in dotted-decimal notation. */
    val sourceIp: String,
    /** Source port number (device-side). */
    val sourcePort: Int,
    /** Destination IP address (remote server). */
    val destinationIp: String,
    /** Destination port number (remote server). */
    val destinationPort: Int,
    /**
     * IP protocol number.
     * @see com.privacyguard.core.packet.IpPacket.PROTO_TCP
     * @see com.privacyguard.core.packet.IpPacket.PROTO_UDP
     */
    val protocol: Int,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Derived Properties
    // ─────────────────────────────────────────────────────────────────────────

    /** Human-readable protocol name. */
    val protocolName: String get() = when (protocol) {
        PROTO_TCP  -> "TCP"
        PROTO_UDP  -> "UDP"
        PROTO_ICMP -> "ICMP"
        else       -> "PROTO($protocol)"
    }

    /**
     * Returns the reverse (response) key — swaps source and destination.
     * Used to look up the session when a packet arrives from the remote server
     * and needs to be forwarded back to the device.
     */
    fun reversed(): SessionKey = copy(
        sourceIp        = destinationIp,
        sourcePort      = destinationPort,
        destinationIp   = sourceIp,
        destinationPort = sourcePort,
    )

    /**
     * Returns true if the destination is a well-known cleartext port.
     */
    val isHttpCleartext: Boolean get() =
        destinationPort == 80 || destinationPort == 8080 || destinationPort == 8000

    /**
     * Returns true if the destination is a standard TLS port.
     */
    val isTls: Boolean get() =
        destinationPort == 443 || destinationPort == 8443

    /**
     * Returns true if this is a DNS query session.
     */
    val isDns: Boolean get() =
        destinationPort == 53 && protocol == PROTO_UDP

    override fun toString(): String =
        "$protocolName $sourceIp:$sourcePort → $destinationIp:$destinationPort"

    // ─────────────────────────────────────────────────────────────────────────
    // Companion
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val PROTO_ICMP = 1
        const val PROTO_TCP  = 6
        const val PROTO_UDP  = 17

        /**
         * Convenience factory that creates a [SessionKey] from an [IpPacket]
         * and a parsed transport-layer port pair.
         */
        fun of(
            srcIp: String,
            srcPort: Int,
            dstIp: String,
            dstPort: Int,
            proto: Int,
        ) = SessionKey(
            sourceIp        = srcIp,
            sourcePort      = srcPort,
            destinationIp   = dstIp,
            destinationPort = dstPort,
            protocol        = proto,
        )
    }
}