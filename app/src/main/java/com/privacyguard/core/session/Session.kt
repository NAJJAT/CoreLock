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

    @Volatile var lastDeviceSeq: Long = 0L
    @Volatile var sendSeq: Long = System.nanoTime() and 0xFFFFFFFFL
    @Volatile var lastAckToDevice: Long = 0L
    /** Sequence number of our SYN-ACK, kept to resend it; -1 until it was sent. */
    @Volatile var synAckSeq: Long = -1L

    /**
     * Device→server bytes already ACKed to the device but not yet taken by the
     * socket (it was full). Flushed by the selector on OP_WRITE. Guarded by
     * synchronizing on the deque itself; [pendingOutBytes] is its total size.
     */
    val pendingOut = java.util.ArrayDeque<java.nio.ByteBuffer>()
    @Volatile var pendingOutBytes: Int = 0

    // ── Server → device flow control and retransmission ─────────────────────
    // Our SYN-ACK carries no window-scale option, so the device's window field
    // is an unscaled byte count (RFC 7323 §2.2).

    /**
     * Segments injected toward the device but not yet ACKed by it, oldest first,
     * kept so a lost one can be resent. Guarded by synchronizing on the deque.
     */
    val unackedToDevice = java.util.ArrayDeque<OutSegment>()
    /** Highest ACK number the device has sent; -1 until the handshake completes. */
    @Volatile var deviceAckedSeq: Long = -1L
    /** Receive window the device last advertised, in bytes. */
    @Volatile var deviceWindow: Int = 0xFFFF
    /** Duplicate-ACK counter for fast retransmit (guarded by [unackedToDevice]). */
    var dupAcks: Int = 0
    /** Current retransmission timeout and how many times the head was resent. */
    var rtoMs: Long = INITIAL_RTO_MS
    var retransmits: Int = 0
    /** Reading from the server is paused until the device or TUN queue has room. */
    @Volatile var readPaused: Boolean = false

    /** The session's channel was switched to the local MITM server: no SYN-ACK on connect. */
    @Volatile var mitmRedirect: Boolean = false

    /** When the filter rules were last re-checked for this live session. */
    @Volatile var filterCheckedAt: Long = 0L

    /** The device sent FIN: shut the socket's output once [pendingOut] drains. */
    @Volatile var deviceFinReceived: Boolean = false
    /** The server closed its side and our FIN went to the device. */
    @Volatile var remoteClosed: Boolean = false

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
     *  - What the first client bytes are (HTTP request line, TLS ClientHello,
     *    QUIC long header); never the port alone
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

    /** App on screen when the session opened; null when unknown (no Usage Access). Set once. */
    @Volatile var foregroundAtStart: Boolean? = null

    /** Screen on when the session opened; null until captured. Set once. */
    @Volatile var screenOnAtStart: Boolean? = null

    /** True once [foregroundAtStart] / [screenOnAtStart] were recorded. */
    @Volatile var appStateCaptured: Boolean = false

    /**
     * Set when PrivacyGuard ended this session on purpose (a rule, a cleartext policy,
     * a DoH endpoint). The closed session is then logged as a blocked attempt even if
     * it never moved a byte.
     */
    @Volatile var blocked: Boolean = false

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

    /** True once a metadata-only log entry has been saved for this session. */
    @Volatile var metadataLogged: Boolean = false

    /** First TLS record from the device, buffered until complete (TcpForwarder only). */
    @Volatile var clientHelloBuf: ByteArray = ByteArray(0)

    /** Opaque per-session state owned by the TlsInterception implementation. */
    @Volatile var captureState: Any? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Derived
    // ─────────────────────────────────────────────────────────────────────────

    val ageMs: Long get() = System.currentTimeMillis() - createdAt
    val idleMs: Long get() = System.currentTimeMillis() - lastActivityAt
    val isCleartext: Boolean get() = encryptionStatus == EncryptionStatus.CLEARTEXT

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
        foregroundAtStart = foregroundAtStart,
        screenOnAtStart = screenOnAtStart,
        isClosed = isClosed,
        blocked = blocked,
        isMitmIntercepted = isMitmIntercepted,
        payloadCount = payloadCount.get()
    )

    override fun toString(): String =
        "Session[$key uid=$ownerUid enc=$encryptionStatus " +
                "sni=$tlsSni mitm=$isMitmIntercepted ↑${bytesFromDevice.get()}B ↓${bytesToDevice.get()}B]"

    companion object {
        const val INITIAL_RTO_MS = 1_000L
    }
}

/**
 * One server→device TCP segment awaiting the device's ACK. [fin] segments carry
 * no data but take one sequence number.
 */
class OutSegment(val seq: Long, val data: ByteArray, val fin: Boolean, @Volatile var sentAt: Long) {
    /** Sequence number just past this segment (mod 2^32). */
    val end: Long get() = (seq + data.size + (if (fin) 1 else 0)) and 0xFFFFFFFFL
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
    val foregroundAtStart: Boolean? = null,
    val screenOnAtStart: Boolean? = null,
    val isClosed: Boolean,
    val blocked: Boolean = false,
    // MITM fields in snapshot
    val isMitmIntercepted: Boolean,
    val payloadCount: Int,
) {
    val totalBytes: Long get() = bytesFromDevice + bytesToDevice
    val ageMs: Long get() = System.currentTimeMillis() - createdAt
    val idleMs: Long get() = System.currentTimeMillis() - lastActivityAt
    val isCleartext: Boolean get() = encryptionStatus == EncryptionStatus.CLEARTEXT
    val isWeakTls: Boolean get() = encryptionStatus == EncryptionStatus.WEAK_TLS ||
            tlsVersion?.isSecure == false
}