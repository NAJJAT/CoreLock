package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted record of a single closed network session.
 * Field definitions follow the BRD §8.1 Connection Entity spec.
 */
@Entity(tableName = "connections")
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

    // ── Encryption (BRD §8.1 — zero payload decryption) ──────────────────────
    /** CLEARTEXT / TLS / WEAK_TLS / UNKNOWN */
    val encryptionStatus: String  = "UNKNOWN",
    /** TLS_1_0 / TLS_1_1 / TLS_1_2 / TLS_1_3 — null for non-TLS sessions. */
    val tlsVersion:       String? = null,

    // ── Behavioral context (feeds MetadataEngine) ─────────────────────────────
    /** True if the owning app was in the background when this session opened. */
    val wasBackground:    Boolean = false,
)
