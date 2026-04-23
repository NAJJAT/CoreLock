package com.privacyguard.core.session

import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.metadata.TlsVersion
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Represents the live state of a single proxied network session.
 *
 * Extended with encryption classification and background-context fields
 * that feed the [com.privacyguard.core.metadata.MetadataEngine] without
 * ever decrypting payload content.
 *
 * Thread safety: individual fields are either immutable, [AtomicLong],
 * [AtomicReference], or @Volatile primitives.
 */
class Session(
    val key:       SessionKey,
    val createdAt: Long = System.currentTimeMillis(),
    val ownerUid:  Int  = -1,
    @Volatile var ownerPackage: String? = null,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Real socket handles
    // ─────────────────────────────────────────────────────────────────────────

    @Volatile var tcpChannel:   SocketChannel?   = null
    @Volatile var udpChannel:   DatagramChannel? = null
    @Volatile var selectionKey: SelectionKey?    = null

    // ─────────────────────────────────────────────────────────────────────────
    // TCP State Machine
    // ─────────────────────────────────────────────────────────────────────────

    enum class TcpState {
        SYN_RECEIVED, SYN_ACK_SENT, ESTABLISHED, FIN_WAIT, CLOSE_WAIT, CLOSED
    }

    val tcpState = AtomicReference(TcpState.SYN_RECEIVED)

    // ─────────────────────────────────────────────────────────────────────────
    // TCP Sequence / Acknowledgment Numbers
    // ─────────────────────────────────────────────────────────────────────────

    @Volatile var lastDeviceSeq:   Long = 0L
    @Volatile var sendSeq:         Long = System.nanoTime() and 0xFFFFFFFFL
    @Volatile var lastAckToDevice: Long = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Traffic Counters
    // ─────────────────────────────────────────────────────────────────────────

    val bytesFromDevice   = AtomicLong(0)
    val bytesToDevice     = AtomicLong(0)
    val packetsFromDevice = AtomicLong(0)
    val packetsToDevice   = AtomicLong(0)

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Volatile var lastActivityAt: Long    = createdAt
    @Volatile var isClosing:      Boolean = false
    @Volatile var isClosed:       Boolean = false

    // ─────────────────────────────────────────────────────────────────────────
    // Hostname Resolution (DNS + SNI — zero decryption)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hostname from TLS ClientHello SNI extension.
     * Set by [com.privacyguard.vpn.inspector.EncryptionEnforcer].
     * This is the most reliable hostname for TLS sessions because it comes
     * directly from the handshake plaintext — even before the cert is sent.
     */
    @Volatile var tlsSni: String? = null

    /**
     * Hostname reverse-mapped from a DNS query whose response contained
     * [key.destinationIp]. Set by [com.privacyguard.vpn.forwarder.DnsHandler].
     */
    @Volatile var resolvedHostname: String? = null

    /** Best-effort hostname: SNI preferred, DNS-resolved as fallback. */
    val hostname: String? get() = tlsSni ?: resolvedHostname

    // ─────────────────────────────────────────────────────────────────────────
    // ★ Encryption Metadata (no decryption required)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Encryption classification from [com.privacyguard.vpn.inspector.EncryptionEnforcer].
     * Derived from:
     *  - Destination port (80=cleartext, 443=TLS)
     *  - TLS ClientHello record-layer version bytes
     *  - supported_versions extension for TLS 1.3 detection
     */
    @Volatile var encryptionStatus: EncryptionStatus = EncryptionStatus.UNKNOWN

    /**
     * TLS version extracted from ClientHello.
     * Null until [encryptionClassified] = true.
     */
    @Volatile var tlsVersion: TlsVersion? = null

    /**
     * True once [com.privacyguard.vpn.inspector.EncryptionEnforcer] has
     * classified this session's encryption posture.
     */
    @Volatile var encryptionClassified: Boolean = false

    // ─────────────────────────────────────────────────────────────────────────
    // ★ Behavioral Context (feeds MetadataEngine)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Whether the owning app was in the background when this session was created.
     * Background connections are a key behavioral risk signal.
     */
    @Volatile var wasBackground: Boolean = false

    // ─────────────────────────────────────────────────────────────────────────
    // Derived
    // ─────────────────────────────────────────────────────────────────────────

    val ageMs:         Long    get() = System.currentTimeMillis() - createdAt
    val idleMs:        Long    get() = System.currentTimeMillis() - lastActivityAt
    val isCleartext:   Boolean get() = encryptionStatus == EncryptionStatus.CLEARTEXT
    val isWeakTls:     Boolean get() = !encryptionStatus.let {
        it == EncryptionStatus.TLS && tlsVersion?.isSecure != false
    }

    fun isTimedOut(timeoutMs: Long) = idleMs > timeoutMs

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun recordOutbound(bytes: Int) {
        bytesFromDevice.addAndGet(bytes.toLong())
        packetsFromDevice.incrementAndGet()
        lastActivityAt = System.currentTimeMillis()
    }

    fun recordInbound(bytes: Int) {
        bytesToDevice.addAndGet(bytes.toLong())
        packetsToDevice.incrementAndGet()
        lastActivityAt = System.currentTimeMillis()
    }

    fun close() {
        if (isClosed) return
        isClosing = true
        isClosed  = true
        tcpState.set(TcpState.CLOSED)
        runCatching { selectionKey?.cancel() }
        runCatching { tcpChannel?.close() }
        runCatching { udpChannel?.close() }
        tcpChannel   = null
        udpChannel   = null
        selectionKey = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Snapshot — immutable, safe for off-thread use
    // ─────────────────────────────────────────────────────────────────────────

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        key               = key,
        createdAt         = createdAt,
        lastActivityAt    = lastActivityAt,
        ownerUid          = ownerUid,
        ownerPackage      = ownerPackage,
        bytesFromDevice   = bytesFromDevice.get(),
        bytesToDevice     = bytesToDevice.get(),
        packetsFromDevice = packetsFromDevice.get(),
        packetsToDevice   = packetsToDevice.get(),
        tcpState          = tcpState.get(),
        hostname          = hostname,
        encryptionStatus  = encryptionStatus,
        tlsVersion        = tlsVersion,
        tlsSni            = tlsSni,
        wasBackground     = wasBackground,
        isClosed          = isClosed,
    )

    override fun toString(): String =
        "Session[$key uid=$ownerUid enc=$encryptionStatus " +
        "sni=$tlsSni ↑${bytesFromDevice.get()}B ↓${bytesToDevice.get()}B]"
}

// ─────────────────────────────────────────────────────────────────────────────
// Snapshot DTO
// ─────────────────────────────────────────────────────────────────────────────

data class SessionSnapshot(
    val key:               SessionKey,
    val createdAt:         Long,
    val lastActivityAt:    Long,
    val ownerUid:          Int,
    val ownerPackage:      String?,
    val bytesFromDevice:   Long,
    val bytesToDevice:     Long,
    val packetsFromDevice: Long,
    val packetsToDevice:   Long,
    val tcpState:          Session.TcpState,
    val hostname:          String?,
    val encryptionStatus:  EncryptionStatus,
    val tlsVersion:        TlsVersion?,
    val tlsSni:            String?,
    val wasBackground:     Boolean,
    val isClosed:          Boolean,
) {
    val totalBytes:  Long    get() = bytesFromDevice + bytesToDevice
    val ageMs:       Long    get() = System.currentTimeMillis() - createdAt
    val idleMs:      Long    get() = System.currentTimeMillis() - lastActivityAt
    val isCleartext: Boolean get() = encryptionStatus == EncryptionStatus.CLEARTEXT
    val isWeakTls:   Boolean get() = encryptionStatus == EncryptionStatus.WEAK_TLS ||
                                     tlsVersion?.isSecure == false
}