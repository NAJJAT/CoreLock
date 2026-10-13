package com.privacyguard.core.metadata

import com.privacyguard.core.session.SessionSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Pillar 1 — Behavioral Metadata Engine.
 *
 * Builds a [ConnectionProfile] per (app × host) from closed session snapshots.
 * No payload decryption — all analysis is on observable metadata.
 *
 * The key is the same as the database's (package, hostname), so one host behind
 * many CDN addresses is one profile, and saving never overwrites one address's
 * numbers with another's. [seed] loads the saved profiles when the VPN starts, so
 * counts keep accumulating across restarts instead of starting again at one.
 */
class MetadataEngine {

    /** Set of known tracker hostnames used for risk scoring. */
    @Volatile var knownTrackers: Set<String> = emptySet()

    val totalRecorded = AtomicLong(0)

    // key = "$packageName||$host"
    private val profiles = ConcurrentHashMap<String, ConnectionProfile>(64)

    /** Profiles saved earlier; ones already recorded this run are kept. */
    fun seed(saved: List<ConnectionProfile>) {
        saved.forEach { profiles.putIfAbsent(profileKey(it.packageName, it.hostname), it) }
    }

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
        // A blocked attempt sent nothing: it is not behaviour of the destination.
        if (snapshot.blocked) return
        val pkg  = snapshot.ownerPackage ?: return
        val host = hostOf(snapshot, sni)
        val key  = profileKey(pkg, host)
        profiles.compute(key) { _, existing ->
            mergeProfile(existing, snapshot, pkg, host, isBackground, encStatus, tlsVer, sni)
        }
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
        // Session start time: the moment the app chose to connect.
        val startedAt = snapshot.createdAt
        val count    = (existing?.connectionCount ?: 0L) + 1L
        val bytesOut = (existing?.totalBytesOut ?: 0L) + snapshot.bytesFromDevice
        val bytesIn  = (existing?.totalBytesIn  ?: 0L) + snapshot.bytesToDevice
        val avgBytes = (bytesOut + bytesIn) / count

        // Intervals between starts: (count - 1) of them. Sessions close out of
        // order, so a start earlier than the last one adds no interval.
        val previousIntervals = ((existing?.connectionCount ?: 0L) - 1L).coerceAtLeast(0L)
        val interval = existing?.let { startedAt - it.lastSeen }?.takeIf { it > 0 }
        val (avgInterval, minInterval) = if (existing == null || interval == null) {
            (existing?.avgIntervalMs ?: 0L) to (existing?.minIntervalMs ?: Long.MAX_VALUE)
        } else {
            val intervals = previousIntervals + 1
            ((existing.avgIntervalMs * previousIntervals + interval) / intervals) to
                minOf(existing.minIntervalMs, interval)
        }

        val bgCount = ((existing?.backgroundRatio ?: 0f) * (count - 1).toFloat()).toLong() + if (isBackground) 1L else 0L
        val bgRatio = bgCount.toFloat() / count.toFloat()

        val hourly = existing?.hourlyDistribution?.copyOf() ?: IntArray(24)
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = startedAt }.get(java.util.Calendar.HOUR_OF_DAY)
        if (hour in 0..23) hourly[hour]++

        // Risk scoring
        val signals = mutableListOf<RiskSignal>()
        if (isKnownTracker(host)) signals += RiskSignal(RiskCode.KNOWN_TRACKER, "Known tracker domain", 6)
        if (encStatus == EncryptionStatus.CLEARTEXT) signals += RiskSignal(RiskCode.CLEARTEXT, "Unencrypted connection", 7)
        if (encStatus == EncryptionStatus.WEAK_TLS) signals += RiskSignal(RiskCode.WEAK_TLS, "Weak TLS version", 4)
        if (bgRatio > 0.8f && count > 5) signals += RiskSignal(RiskCode.BACKGROUND_ONLY, "Mostly background connections", 5)
        if (avgInterval in 1L..60_000L && count > 10 && !com.privacyguard.app.core.detection.KnownPushHosts.isPushHost(host)) {
            signals += RiskSignal(RiskCode.BEACON_PATTERN, "Regular beacon interval", 6)
        }

        val riskScore = (signals.sumOf { it.severity } * 10).coerceIn(0, 100)

        return ConnectionProfile(
            packageName           = pkg,
            hostname              = host,
            destinationIp         = snapshot.key.destinationIp,
            destinationPort       = snapshot.key.destinationPort,
            connectionCount       = count,
            totalBytesOut         = bytesOut,
            totalBytesIn          = bytesIn,
            avgBytesPerConnection = avgBytes,
            firstSeen             = minOf(existing?.firstSeen ?: startedAt, startedAt),
            lastSeen              = maxOf(existing?.lastSeen ?: startedAt, startedAt),
            avgIntervalMs         = avgInterval,
            minIntervalMs         = minInterval,
            backgroundRatio       = bgRatio,
            hourlyDistribution    = hourly,
            // The latest session's encryption (an UNKNOWN close keeps what was known).
            encryptionStatus      = if (encStatus == EncryptionStatus.UNKNOWN) existing?.encryptionStatus ?: encStatus else encStatus,
            tlsVersion            = tlsVer ?: existing?.tlsVersion,
            sniHostname           = sni ?: existing?.sniHostname,
            riskScore             = riskScore,
            riskSignals           = signals,
        )
    }

    /** The host or any parent domain is listed ("ads.tracker.com" matches "tracker.com"). */
    private fun isKnownTracker(host: String): Boolean {
        val trackers = knownTrackers
        if (trackers.isEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in trackers) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndexOf('.')) return false
            h = h.substring(dot + 1)
        }
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

    companion object {
        /** SNI first, then the DNS name, then the bare address: one rule everywhere. */
        fun hostOf(snapshot: SessionSnapshot, sni: String? = snapshot.tlsSni): String =
            sni?.takeIf { it.isNotBlank() }
                ?: snapshot.hostname?.takeIf { it.isNotBlank() }
                ?: snapshot.key.destinationIp

        private fun profileKey(pkg: String, host: String) = "$pkg||$host"
    }
}
