package com.privacyguard.app.ui.traffic

import com.privacyguard.app.core.behavior.BehaviorDnaAnalyzer
import com.privacyguard.app.core.behavior.BehaviorFinding
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.app.data.repository.MetadataRepo
import java.util.Calendar

/** Every number on Home that opens a list. The name says the window. */
enum class EvidenceKind {
    BLOCKED_24H, CLEARTEXT_24H, ENCRYPTION_24H, NIGHT_24H, BACKGROUND_24H, IPV6_24H, UPLOADS_24H,
    BEACONS_24H, ENTROPY_7D, BEHAVIOR_7D, CONNECTIONS_7D, BLOCKED_7D, ANOMALIES_7D, DATA_7D,
    // Stats tiles (last 24 h).
    CONNECTIONS_24H, DATA_24H;

    val windowMs: Long get() = if (name.endsWith("_7D")) HomeEvidence.WEEK_MS else HomeEvidence.DAY_MS
}

/** A repeating (app, host) pair: same destination contacted at near-constant intervals. */
data class BeaconPattern(
    val packageName: String,
    val host: String,
    val count: Int,
    val meanIntervalMs: Long,
    val lastSeen: Long,
)

sealed interface Evidence {
    val size: Int

    data class Rows(val rows: List<TrafficRow>) : Evidence { override val size get() = rows.size }
    data class Anomalies(val items: List<DnsAnomalyEntity>) : Evidence { override val size get() = items.size }
    data class Beacons(val items: List<BeaconPattern>) : Evidence { override val size get() = items.size }
    data class Findings(val items: List<BehaviorFinding>) : Evidence { override val size get() = items.size }
}

/**
 * Builds the list behind each Home number. Home shows `load(kind, at).size` (or a
 * value derived from the same list) and the drill-down screen calls `load` with the
 * same [at], so the number shown and the length of the list opened are equal by
 * construction. Lists are complete: no LIMIT anywhere.
 */
class HomeEvidence(private val db: AppDatabase) {

    suspend fun load(kind: EvidenceKind, at: Long): Evidence {
        val from = at - kind.windowMs
        return when (kind) {
            // Blocked lookups and blocked connection attempts: the BlockStats definition.
            EvidenceKind.BLOCKED_24H, EvidenceKind.BLOCKED_7D -> Evidence.Rows(
                (db.dnsQueryDao().blockedBetween(from, at).map { TrafficRows.fromLookup(it) } +
                    db.connectionDao().blockedBetween(from, at).map { TrafficRows.fromConnection(it) })
                    .sortedByDescending { it.timestamp }
            )
            EvidenceKind.CLEARTEXT_24H, EvidenceKind.ENCRYPTION_24H, EvidenceKind.CONNECTIONS_7D,
            EvidenceKind.DATA_7D, EvidenceKind.CONNECTIONS_24H, EvidenceKind.DATA_24H, EvidenceKind.NIGHT_24H, EvidenceKind.BACKGROUND_24H,
            EvidenceKind.IPV6_24H, EvidenceKind.UPLOADS_24H -> rows(select(kind, connections(from, at)))
            EvidenceKind.BEACONS_24H -> Evidence.Beacons(beacons(connections(from, at)))
            EvidenceKind.ENTROPY_7D ->
                // Random-looking names only; repeated lookups of one name are a different signal.
                Evidence.Anomalies(anomalies(from, at).filter { it.anomalyType == "HIGH_ENTROPY" })
            EvidenceKind.ANOMALIES_7D -> Evidence.Anomalies(anomalies(from, at))
            EvidenceKind.BEHAVIOR_7D -> Evidence.Findings(behaviorFindings(at))
        }
    }

    /** Totals that are cheaper as SQL aggregates; same WHERE clause as the lists above. */
    suspend fun connectionCount(kind: EvidenceKind, at: Long): Int =
        db.connectionDao().countBetween(at - kind.windowMs, at)

    suspend fun bytes(kind: EvidenceKind, at: Long): Long =
        db.connectionDao().bytesBetween(at - kind.windowMs, at)

    suspend fun blockedCount(kind: EvidenceKind, at: Long): Int =
        com.privacyguard.app.data.repository.BlockStats(db).between(at - kind.windowMs, at).total

    /**
     * Behaviour changes seen in the 7 days before [at]: the baseline is learned from
     * all saved profiles, but only findings about destinations active in the window count.
     */
    suspend fun behaviorFindings(at: Long): List<BehaviorFinding> {
        val repo = MetadataRepo(db.connectionProfileDao())
        val profiles = db.connectionProfileDao().allProfiles().map { repo.toDomainForDashboard(it) }
        val from = at - WEEK_MS
        val active = profiles.filter { it.lastSeen in from..at }.map { it.packageName to it.hostname }.toSet()
        return BehaviorDnaAnalyzer.summarizeAll(profiles).flatMap { it.findings }
            .filter { (it.packageName to it.hostname) in active }
    }

    private suspend fun connections(from: Long, to: Long): List<ConnectionEntity> = db.connectionDao().between(from, to)

    private suspend fun anomalies(from: Long, to: Long): List<DnsAnomalyEntity> =
        db.dnsAnomalyDao().since(from).filter { it.timestamp <= to }

    private suspend fun rows(list: List<ConnectionEntity>): Evidence.Rows {
        val hosts = list.mapNotNull { it.sniHostname ?: it.domain }.toSet()
        val anomalies = if (hosts.isEmpty()) emptyMap() else
            db.dnsAnomalyDao().since(0).filter { it.domain in hosts }.groupBy { it.domain }
        return Evidence.Rows(list.map { TrafficRows.fromConnection(it, anomalies) })
    }

    companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val WEEK_MS = 7L * DAY_MS

        /**
         * The connections behind a connection-based number, from all connections in
         * its window. Home applies this to the same window, so counts match the list.
         */
        fun select(kind: EvidenceKind, inWindow: List<ConnectionEntity>): List<ConnectionEntity> = when (kind) {
            EvidenceKind.CLEARTEXT_24H -> inWindow.filter { it.encryptionStatus == "CLEARTEXT" && !it.wasBlocked }
            // Night and screen off: the user's own late-night use is not "unexpected".
            EvidenceKind.NIGHT_24H -> inWindow.filter { isNight(it.timestamp) && it.screenOn == false }
            EvidenceKind.BACKGROUND_24H -> inWindow.filter { it.foreground == false }
            EvidenceKind.IPV6_24H -> inWindow.filter { it.isIPv6 || it.destinationIpv6 != null || it.destinationIp.contains(':') }
            // Background only: the user sharing a video is not exfiltration.
            EvidenceKind.UPLOADS_24H -> inWindow.filter { it.bytesSent > 0 && it.foreground == false }.sortedByDescending { it.bytesSent }
            EvidenceKind.DATA_7D, EvidenceKind.DATA_24H -> inWindow.sortedByDescending { it.bytesSent + it.bytesReceived }
            else -> inWindow
        }

        /** Encryption of a set of connections, as every screen reports it. */
        data class EncryptionShare(val encrypted: Int, val cleartext: Int, val unknown: Int) {
            /** Connections whose encryption we could see (a handshake, or an HTTP request). */
            val identified: Int get() = encrypted + cleartext
            /** Encrypted share of [identified]; 1 when nothing could be identified yet. */
            val ratio: Float get() = if (identified == 0) 1f else encrypted.toFloat() / identified
        }

        fun encryptionShare(connections: List<ConnectionEntity>): EncryptionShare {
            val allowed = connections.filter { !it.wasBlocked }
            val encrypted = allowed.count { isEncrypted(it) }
            val cleartext = allowed.count { it.encryptionStatus == "CLEARTEXT" }
            return EncryptionShare(encrypted, cleartext, allowed.size - encrypted - cleartext)
        }

        /** Counted as encrypted for the "Encryption health" percentage. */
        fun isEncrypted(c: ConnectionEntity): Boolean =
            c.encryptionStatus in ENCRYPTED_STATUSES || c.tlsVersion?.startsWith("TLS_1_") == true

        /** TLS of any version (weak TLS still encrypts) and QUIC, which always runs TLS 1.3. */
        private val ENCRYPTED_STATUSES = setOf("TLS", "TLS_1_2", "TLS_1_3", "WEAK_TLS", "QUIC")

        /** "Night" = 00:00–05:59 local time. */
        fun isNight(timestamp: Long): Boolean =
            Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY) in 0..5

        /**
         * At least 6 connections to the same host from the same app with gaps of
         * 5 s–10 min whose spread is under 25 % of the mean: a timer, not a person.
         */
        fun beacons(connections: List<ConnectionEntity>): List<BeaconPattern> =
            connections
                // Push channels and keep-alives run on a timer by design.
                .filter { !com.privacyguard.app.core.detection.KnownPushHosts.isPushHost(it.sniHostname ?: it.domain) }
                .groupBy { it.packageName to (it.sniHostname ?: it.domain ?: it.destinationIp) }
                .mapNotNull { (key, group) ->
                    if (group.size < 6) return@mapNotNull null
                    val intervals = group.map { it.timestamp }.sorted().zipWithNext { a, b -> b - a }
                        .filter { it in 5_000L..10L * 60L * 1000L }
                    if (intervals.size < 5) return@mapNotNull null
                    val avg = intervals.average()
                    val sd = kotlin.math.sqrt(intervals.map { (it - avg) * (it - avg) }.average())
                    if (sd / avg >= 0.25) return@mapNotNull null
                    BeaconPattern(key.first, key.second, group.size, avg.toLong(), group.maxOf { it.timestamp })
                }
                .sortedByDescending { it.count }
    }
}
