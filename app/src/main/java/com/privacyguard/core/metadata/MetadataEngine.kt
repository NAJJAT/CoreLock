package com.privacyguard.core.metadata

import com.privacyguard.core.session.SessionSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Pillar 1 — Behavioral Metadata Engine.
 *
 * Builds a [ConnectionProfile] per (app × destination) from closed session
 * snapshots. No payload decryption — all analysis is on observable metadata.
 */
class MetadataEngine {

    /** Set of known tracker hostnames used for risk scoring. */
    @Volatile var knownTrackers: Set<String> = emptySet()

    val totalRecorded = AtomicLong(0)

    // key = "$packageName||$destinationIp:$destinationPort"
    private val profiles = ConcurrentHashMap<String, ConnectionProfile>(64)

    // ─────────────────────────────────────────────────────────────────────────
    // Recording
    // ─────────────────────────────────────────────────────────────────────────

    fun record(
        snapshot:     SessionSnapshot,
        isBackground: Boolean,
        encStatus:    EncryptionStatus,
        tlsVer:       TlsVersion?,
        sni:          String?,
    ) {
        val pkg  = snapshot.ownerPackage ?: return
        val host = snapshot.hostname ?: snapshot.key.destinationIp
        val key  = profileKey(pkg, snapshot.key.destinationIp, snapshot.key.destinationPort)

        val existing = profiles[key]
        val updated  = mergeProfile(existing, snapshot, pkg, host, isBackground, encStatus, tlsVer, sni)
        profiles[key] = updated
        totalRecorded.incrementAndGet()
    }

    private fun mergeProfile(
        existing:     ConnectionProfile?,
        snapshot:     SessionSnapshot,
        pkg:          String,
        host:         String,
        isBackground: Boolean,
        encStatus:    EncryptionStatus,
        tlsVer:       TlsVersion?,
        sni:          String?,
    ): ConnectionProfile {
        val now      = System.currentTimeMillis()
        val count    = (existing?.connectionCount ?: 0L) + 1L
        val bytesOut = (existing?.totalBytesOut ?: 0L) + snapshot.bytesFromDevice
        val bytesIn  = (existing?.totalBytesIn  ?: 0L) + snapshot.bytesToDevice
        val avgBytes = if (count > 0) (bytesOut + bytesIn) / count else 0L
        val firstSeen = existing?.firstSeen ?: now

        val intervalMs = if (existing != null) now - existing.lastSeen else 0L
        val avgInterval = if (existing != null && existing.connectionCount > 0)
            ((existing.avgIntervalMs * existing.connectionCount) + intervalMs) / count
        else 0L
        val minInterval = if (existing != null) minOf(existing.minIntervalMs, intervalMs) else intervalMs

        val bgCount = ((existing?.backgroundRatio ?: 0f) * ((count - 1).toFloat())).toLong() + if (isBackground) 1L else 0L
        val bgRatio = bgCount.toFloat() / count.toFloat()

        // Hourly distribution
        val hourly = existing?.hourlyDistribution?.copyOf() ?: IntArray(24)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        hourly[hour]++

        // Risk scoring
        val signals = mutableListOf<RiskSignal>()
        if (host in knownTrackers) signals += RiskSignal(RiskCode.KNOWN_TRACKER, "Known tracker domain", 6)
        if (encStatus == EncryptionStatus.CLEARTEXT) signals += RiskSignal(RiskCode.CLEARTEXT, "Unencrypted connection", 7)
        if (encStatus == EncryptionStatus.WEAK_TLS) signals += RiskSignal(RiskCode.WEAK_TLS, "Weak TLS version", 4)
        if (bgRatio > 0.8f && count > 5) signals += RiskSignal(RiskCode.BACKGROUND_ONLY, "Mostly background connections", 5)
        if (avgInterval in 1L..60_000L && count > 10) signals += RiskSignal(RiskCode.BEACON_PATTERN, "Regular beacon interval", 6)

        val riskScore = (signals.sumOf { it.severity } * 10).coerceIn(0, 100)

        return ConnectionProfile(
            packageName           = pkg,
            hostname              = sni ?: host,
            destinationIp         = snapshot.key.destinationIp,
            destinationPort       = snapshot.key.destinationPort,
            connectionCount       = count,
            totalBytesOut         = bytesOut,
            totalBytesIn          = bytesIn,
            avgBytesPerConnection = avgBytes,
            firstSeen             = firstSeen,
            lastSeen              = now,
            avgIntervalMs         = avgInterval,
            minIntervalMs         = minInterval,
            backgroundRatio       = bgRatio,
            hourlyDistribution    = hourly,
            encryptionStatus      = encStatus,
            tlsVersion            = tlsVer,
            sniHostname           = sni,
            riskScore             = riskScore,
            riskSignals           = signals,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────────────────

    fun allProfiles(): List<ConnectionProfile> = profiles.values.toList()

    fun profilesForApp(pkg: String): List<ConnectionProfile> =
        profiles.values.filter { it.packageName == pkg }

    fun riskyProfiles(minScore: Int = 40): List<ConnectionProfile> =
        profiles.values.filter { it.riskScore >= minScore }.sortedByDescending { it.riskScore }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun profileKey(pkg: String, ip: String, port: Int) = "$pkg||$ip:$port"
}
