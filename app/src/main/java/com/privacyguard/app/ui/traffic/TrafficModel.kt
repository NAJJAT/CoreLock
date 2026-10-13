package com.privacyguard.app.ui.traffic

import com.privacyguard.app.core.tracker.DomainClass
import com.privacyguard.app.core.tracker.DomainClassifier
import com.privacyguard.app.core.tracker.DomainPurpose
import com.privacyguard.app.core.tracker.TrackerCategory
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.app.data.db.DnsQueryEntity

/** Where the hostname of a row came from. */
enum class HostSource {
    /** Server name the app sent in the TLS handshake (most reliable). */
    TLS_SNI,
    /** Name the app looked up via DNS before connecting. */
    DNS,
    /** No name seen: only the IP address is known. */
    IP_ONLY,
}

/** Why a row is highlighted. Every reason is something we observed, not a guess. */
sealed interface FlagReason {
    data class KnownTracker(val name: String, val category: TrackerCategory) : FlagReason
    data object Blocked : FlagReason
    data object ScreenOff : FlagReason
    data object Background : FlagReason
    data object Cleartext : FlagReason
    data class DnsAnomaly(val type: String) : FlagReason
}

/**
 * One line in a drill-down list: either a connection that carried data, or a DNS
 * lookup (blocked lookups never become connections, so they only exist here).
 */
data class TrafficRow(
    val id: String,
    val isLookup: Boolean,
    val timestamp: Long,
    val packageName: String,
    /** Hostname, or null when only the IP is known. */
    val host: String?,
    val ip: String?,
    val port: Int?,
    val protocol: String?,
    val source: HostSource,
    val bytesSent: Long,
    val bytesReceived: Long,
    /** null = not recorded (older rows). */
    val screenOn: Boolean?,
    /** true = app on screen, false = background, null = unknown (no Usage Access / older rows). */
    val foreground: Boolean?,
    val blocked: Boolean,
    val encryption: String?,
    val classification: DomainClass?,
    val reasons: List<FlagReason>,
    /** Same test as Home's "Encryption health" (HomeEvidence.isEncrypted). */
    val encrypted: Boolean = false,
    /** Inside your own network (router, Chromecast, printer): not internet traffic. */
    val local: Boolean = false,
    /** The OS checking for internet access (captive portal, MobileWips). */
    val probe: Boolean = false,
) {
    val flagged: Boolean get() = reasons.isNotEmpty()
}

object TrafficRows {

    fun fromConnection(c: ConnectionEntity, anomalies: Map<String, List<DnsAnomalyEntity>> = emptyMap()): TrafficRow {
        val host = c.sniHostname?.takeIf { it.isNotBlank() } ?: c.domain?.takeIf { it.isNotBlank() }
        val source = when {
            !c.sniHostname.isNullOrBlank() -> HostSource.TLS_SNI
            !c.domain.isNullOrBlank() -> HostSource.DNS
            else -> HostSource.IP_ONLY
        }
        val cls = host?.let { DomainClassifier.classify(it, c.packageName.ifBlank { null }) }
        // Rows from before v10 carry the old wasBackground flag, which was "true" for
        // every other app (see AppTracker); show those as unknown rather than background.
        val fg = c.foreground
        val screenOn = c.screenOn
        return TrafficRow(
            id = "c${c.id}",
            isLookup = false,
            timestamp = c.timestamp,
            packageName = c.packageName,
            host = host,
            ip = c.destinationIp.ifBlank { null },
            port = c.destinationPort.takeIf { it > 0 },
            protocol = c.protocol,
            source = source,
            bytesSent = c.bytesSent,
            bytesReceived = c.bytesReceived,
            screenOn = screenOn,
            foreground = fg,
            blocked = c.wasBlocked,
            encryption = c.encryptionStatus,
            classification = cls,
            reasons = reasons(cls, c.wasBlocked, screenOn, fg, HomeEvidence.isInternetCleartext(c), host?.let { near(anomalies[it], c.timestamp) }),
            encrypted = HomeEvidence.isEncrypted(c),
            local = com.privacyguard.app.core.network.LocalNetwork.isLocal(c),
            probe = com.privacyguard.app.core.network.ConnectivityProbes.isProbe(c),
        )
    }

    fun fromLookup(q: DnsQueryEntity, anomalies: Map<String, List<DnsAnomalyEntity>> = emptyMap()): TrafficRow {
        val pkg = q.appPackage.takeIf { it != UNKNOWN_PACKAGE }.orEmpty()
        val cls = DomainClassifier.classify(q.domain, pkg.ifBlank { null })
        val screenOn = !q.phoneWasIdle
        return TrafficRow(
            id = "d${q.id}",
            isLookup = true,
            timestamp = q.timestamp,
            packageName = pkg,
            host = q.domain,
            ip = null,
            port = null,
            protocol = null,
            source = HostSource.DNS,
            bytesSent = 0L,
            bytesReceived = 0L,
            screenOn = screenOn,
            foreground = q.appForeground,
            blocked = q.wasBlocked,
            encryption = null,
            classification = cls,
            reasons = reasons(cls, q.wasBlocked, screenOn, q.appForeground, false, near(anomalies[q.domain], q.timestamp)),
        )
    }

    private fun reasons(
        cls: DomainClass?,
        blocked: Boolean,
        screenOn: Boolean?,
        foreground: Boolean?,
        cleartext: Boolean,
        anomalies: List<DnsAnomalyEntity>?,
    ): List<FlagReason> = buildList {
        if (cls?.trackerName != null && cls.trackerCategory != null &&
            cls.purpose != DomainPurpose.FIRST_PARTY) add(FlagReason.KnownTracker(cls.trackerName, cls.trackerCategory))
        if (blocked) add(FlagReason.Blocked)
        if (screenOn == false) add(FlagReason.ScreenOff)
        if (foreground == false && screenOn != false) add(FlagReason.Background)
        if (cleartext) add(FlagReason.Cleartext)
        anomalies?.map { it.anomalyType }?.distinct()?.forEach { add(FlagReason.DnsAnomaly(it)) }
    }

    /** Only anomalies around the row's own time explain that row (not one from another day). */
    private fun near(list: List<DnsAnomalyEntity>?, time: Long): List<DnsAnomalyEntity>? =
        list?.filter { kotlin.math.abs(it.timestamp - time) <= ANOMALY_WINDOW_MS }

    private const val ANOMALY_WINDOW_MS = 10L * 60L * 1000L

    /** DnsHandler stores lookups it cannot attribute to an app under this name. */
    const val UNKNOWN_PACKAGE = "Unknown"
}

/** Domains contacted by one app, grouped by what they are for. */
data class PurposeGroup(
    val purpose: DomainPurpose,
    /** Company for ads/analytics/tracking/service groups; null for first-party, CDN and unknown. */
    val company: String?,
    val domains: List<DomainUse>,
) {
    val connections: Int get() = domains.sumOf { it.connections }
    val lookups: Int get() = domains.sumOf { it.lookups }
    val bytes: Long get() = domains.sumOf { it.bytesSent + it.bytesReceived }
}

data class DomainUse(
    val host: String,
    val classification: DomainClass,
    val connections: Int,
    val lookups: Int,
    val blockedLookups: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
    val lastSeen: Long,
)

object PurposeGroups {
    private val order = listOf(
        DomainPurpose.FIRST_PARTY, DomainPurpose.ADS, DomainPurpose.ANALYTICS, DomainPurpose.TRACKING,
        DomainPurpose.CDN, DomainPurpose.SERVICE, DomainPurpose.UNKNOWN,
    )

    fun build(rows: List<TrafficRow>): List<PurposeGroup> {
        val byHost = rows.filter { it.host != null && it.classification != null }.groupBy { it.host!! }
        val uses = byHost.map { (host, hostRows) ->
            val conns = hostRows.filter { !it.isLookup }
            val lookups = hostRows.filter { it.isLookup }
            DomainUse(
                host = host,
                classification = hostRows.first().classification!!,
                connections = conns.size,
                lookups = lookups.size,
                blockedLookups = lookups.count { it.blocked },
                bytesSent = conns.sumOf { it.bytesSent },
                bytesReceived = conns.sumOf { it.bytesReceived },
                lastSeen = hostRows.maxOf { it.timestamp },
            )
        }
        return uses
            .groupBy { use ->
                val p = use.classification.purpose
                p to when (p) {
                    DomainPurpose.FIRST_PARTY, DomainPurpose.CDN, DomainPurpose.UNKNOWN -> null
                    else -> use.classification.company
                }
            }
            .map { (key, list) ->
                PurposeGroup(key.first, key.second, list.sortedByDescending { it.connections + it.lookups })
            }
            .sortedWith(compareBy<PurposeGroup> { order.indexOf(it.purpose) }.thenByDescending { it.connections + it.lookups })
    }
}
