package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted record of a single closed network session.
 * Field definitions follow the BRD §8.1 Connection Entity spec.
 */
// Nearly every query filters by timestamp; per-app history filters by appUid
// and sorts by timestamp. Keep in sync with AppDatabase.MIGRATION_4_5 / MIGRATION_9_10.
@Entity(
    tableName = "connections",
    indices = [
        Index("timestamp"),
        Index("appUid", "timestamp"),
        Index("packageName", "timestamp"),
    ],
)
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // ── Identity ──────────────────────────────────────────────────────────────
    val appUid:           Int,
    val appName:          String  = "",
    val packageName:      String  = "",

    // ── Addresses ─────────────────────────────────────────────────────────────
    val destinationIp:    String  = "",
    val destinationPort:  Int     = 0,
    /** Full IPv6 destination in compressed notation (null for IPv4). */
    val destinationIpv6:  String? = null,
    /** True when destinationIp contains ':' (IPv6 session). */
    val isIPv6:           Boolean = false,

    // ── Hostname (DNS-resolved or SNI-extracted) ───────────────────────────────
    val domain:           String? = null,
    /** SNI hostname from TLS ClientHello — preferred over DNS-resolved. */
    val sniHostname:      String? = null,

    // ── Protocol ──────────────────────────────────────────────────────────────
    val protocol:         String  = "TCP",

    // ── Traffic ───────────────────────────────────────────────────────────────
    val bytesSent:        Long    = 0L,
    val bytesReceived:    Long    = 0L,

    // ── Timing ────────────────────────────────────────────────────────────────
    val timestamp:        Long    = System.currentTimeMillis(),
    val durationMs:       Long    = 0L,

    // ── Decision ──────────────────────────────────────────────────────────────
    val wasBlocked:       Boolean = false,
    /**
     * BlockSource name when the server name stopped the connection (SNI, DOH), not
     * DNS; null otherwise and for rows written before database version 13.
     */
    val blockSource:      String? = null,

    // ── Encryption (BRD §8.1 — zero payload decryption) ──────────────────────
    /** EncryptionStatus name: CLEARTEXT (HTTP seen), TLS / TLS_1_2 / TLS_1_3 / WEAK_TLS, QUIC, or UNKNOWN. */
    val encryptionStatus: String  = "UNKNOWN",
    /** TLS_1_0 / TLS_1_1 / TLS_1_2 / TLS_1_3 — null for non-TLS sessions. */
    val tlsVersion:       String? = null,

    // ── Behavioral context (feeds MetadataEngine) ─────────────────────────────
    /** True if the owning app was known to be in the background when this session opened. */
    val wasBackground:    Boolean = false,
    /**
     * Whether the owning app was on screen when the session opened: null when unknown
     * (no Usage Access, or rows recorded before this was tracked).
     */
    val foreground:       Boolean? = null,
    /** Whether the screen was on when the session opened; null for older rows. */
    val screenOn:         Boolean? = null,
    /** Network the session used (NetworkIdentity key); null for older rows. */
    val networkKey:       String? = null,
)

/**
 * The host every screen shows for a connection: the TLS SNI the app sent (what it
 * actually asked for), else the DNS name that resolved to this address, else null.
 */
val ConnectionEntity.host: String?
    get() = sniHostname?.takeIf { it.isNotBlank() } ?: domain?.takeIf { it.isNotBlank() }
