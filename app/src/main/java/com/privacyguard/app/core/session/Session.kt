package com.privacyguard.core.session

import com.privacyguard.core.metadata.EncryptionStatus
import com.privacyguard.core.metadata.TlsVersion
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

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
    val key: SessionKey,
    val createdAt: Long = System.currentTimeMillis(),
    @Volatile var ownerUid: Int = -1,
    @Volatile var ownerPackage: String? = null,
) {
    // ─────────────────────────────────────────────────────────────────────────
    // Real socket handles
    // ─────────────────────────────────────────────────────────────────────────

    @Volatile var tcpChannel: SocketChannel? = null
    @Volatile var udpChannel: DatagramChannel? = null
    @Volatile var selectionKey: SelectionKey? = null

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

    /** The device's initial sequence number, from its SYN. */
    @Volatile var lastDeviceSeq: Long = 0L
    /** Our initial sequence number towards the device (the SYN-ACK's seq). */
    val localIsn: Long = System.nanoTime() and 0xFFFFFFFFL
    /** Next sequence number we send to the device; always kept modulo 2^32. */
    @Volatile var sendSeq: Long = localIsn
    /** Next sequence number we expect from the device (everything before it is ACKed). */
    @Volatile var lastAckToDevice: Long = 0L
    @Volatile var synAckSent: Boolean = false

    // ── Flow control towards the device ──────────────────────────────────────
    // We may only send what the device's receive window allows; anything beyond
    // it is dropped by the device and, since we never retransmit, the connection
    // would stall. Reading from the server pauses while the window is full.

    /** Highest sequence number the device has acknowledged. */
    @Volatile var deviceAcked: Long = 0L
    /** The device's receive window in bytes (already scaled). */
    @Volatile var deviceWindow: Long = 65_535L
    /** Window scale shift from the device's SYN, or -1 if it did not offer scaling. */
    @Volatile var deviceWindowShift: Int = -1
    /** True while reading from the server is paused for a full device window. */
    @Volatile var readPaused: Boolean = false

    // ── Device → server write buffer ─────────────────────────────────────────
    // Bytes the device sent (and we ACKed) that the server socket could not take
    // yet. Guarded by [outLock]; flushed by the selector thread on OP_WRITE.

    val outLock = Any()
    val outPending = ArrayDeque<java.nio.ByteBuffer>()
    @Volatile var outPendingBytes: Int = 0
    /** The device sent FIN; shut the server side down once [outPending] drains. */
    @Volatile var shutdownAfterFlush: Boolean = false
    /** True after we advertised a shrunken window, so a drained buffer sends an update. */
    @Volatile var windowLimited: Boolean = false

    /** The device has sent FIN (and we ACKed it). */
    @Volatile var deviceFinReceived: Boolean = false
    /** The server closed its side and we sent FIN to the device. */
    @Volatile var finSentToDevice: Boolean = false

    // ─────────────────────────────────────────────────────────────────────────
    // Traffic Counters
    // ─────────────────────────────────────────────────────────────────────────

    val bytesFromDevice = AtomicLong(0)
    val bytesToDevice = AtomicLong(0)
    val packetsFromDevice = AtomicLong(0)
    val packetsToDevice = AtomicLong(0)

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Volatile var lastActivityAt: Long = createdAt
    /** Measured time for the upstream TCP connect (SYN → connected), or 0 if unknown. */
    @Volatile var connectMs: Long = 0L
    @Volatile var isClosing: Boolean = false
    @Volatile var isClosed: Boolean = false

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
    // Encryption Metadata (no decryption required)
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
    // Behavioral Context (feeds MetadataEngine)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Whether the owning app was in the background when this session was created.
     * Background connections are a key behavioral risk signal.
     */
    @Volatile var wasBackground: Boolean = false

    // ─────────────────────────────────────────────────────────────────────────
    // ★ MITM (Man-in-the-Middle) Fields (Enterprise Feature)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Whether this session is currently being intercepted by the MITM engine.
     * Set to true when TLS interception is active for this session.
     */
    @Volatile
    var isMitmIntercepted: Boolean = false

    /**
     * Counter of payload chunks intercepted for this session.
     * Used for statistics and debugging.
     */
    val payloadCount = AtomicInteger(0)

    /**
     * TLS ClientHello bytes to replay into the MITM local port once the channel connects.
     * Set by TcpForwarder when redirecting a session to the MitmEngine's server socket.
     */
    @Volatile var pendingMitmData: ByteArray? = null

    /** True once a metadata-only log entry has been saved for this session. */
    @Volatile var metadataLogged: Boolean = false

    // Per-session TCP segment accumulators so split HTTP request/response bodies
    // (headers in segment N, body in segment N+1) are reassembled before parsing.
    @Volatile var httpOutBytes: ByteArray = ByteArray(0)  // device → server (requests)
    @Volatile var httpInBytes:  ByteArray = ByteArray(0)  // server → device (responses)

    // ─────────────────────────────────────────────────────────────────────────
    // Derived
    // ─────────────────────────────────────────────────────────────────────────

    val ageMs: Long get() = System.currentTimeMillis() - createdAt
    val idleMs: Long get() = System.currentTimeMillis() - lastActivityAt
    val isCleartext: Boolean get() = encryptionStatus == EncryptionStatus.CLEARTEXT
    val isWeakTls: Boolean get() = !encryptionStatus.let {
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
        isClosed = true
        tcpState.set(TcpState.CLOSED)
        runCatching { selectionKey?.cancel() }
        runCatching { tcpChannel?.close() }
        runCatching { udpChannel?.close() }
        tcpChannel = null
        udpChannel = null
        selectionKey = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Snapshot — immutable, safe for off-thread use
    // ─────────────────────────────────────────────────────────────────────────

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        key = key,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
        ownerUid = ownerUid,
        ownerPackage = ownerPackage,
        bytesFromDevice = bytesFromDevice.get(),
        bytesToDevice = bytesToDevice.get(),
        packetsFromDevice = packetsFromDevice.get(),
        packetsToDevice = packetsToDevice.get(),
        tcpState = tcpState.get(),
        hostname = hostname,
        encryptionStatus = encryptionStatus,
        tlsVersion = tlsVersion,
        tlsSni = tlsSni,
        wasBackground = wasBackground,
        isClosed = isClosed,
        isMitmIntercepted = isMitmIntercepted,
        payloadCount = payloadCount.get(),
        connectMs = connectMs,
    )

    override fun toString(): String =
        "Session[$key uid=$ownerUid enc=$encryptionStatus " +
                "sni=$tlsSni mitm=$isMitmIntercepted ↑${bytesFromDevice.get()}B ↓${bytesToDevice.get()}B]"
}

// ─────────────────────────────────────────────────────────────────────────────
// Snapshot DTO
// ─────────────────────────────────────────────────────────────────────────────

data class SessionSnapshot(
    val key: SessionKey,
    val createdAt: Long,
    val lastActivityAt: Long,
    val ownerUid: Int,
    val ownerPackage: String?,
    val bytesFromDevice: Long,
    val bytesToDevice: Long,
    val packetsFromDevice: Long,
    val packetsToDevice: Long,
    val tcpState: Session.TcpState,
    val hostname: String?,
    val encryptionStatus: EncryptionStatus,
    val tlsVersion: TlsVersion?,
    val tlsSni: String?,
    val wasBackground: Boolean,
    val isClosed: Boolean,
    // MITM fields in snapshot
    val isMitmIntercepted: Boolean,
    val payloadCount: Int,
    val connectMs: Long = 0L,
) {
    val totalBytes: Long get() = bytesFromDevice + bytesToDevice
    val ageMs: Long get() = System.currentTimeMillis() - createdAt
    val idleMs: Long get() = System.currentTimeMillis() - lastActivityAt
    val isCleartext: Boolean get() = encryptionStatus == EncryptionStatus.CLEARTEXT
    val isWeakTls: Boolean get() = encryptionStatus == EncryptionStatus.WEAK_TLS ||
            tlsVersion?.isSecure == false
}