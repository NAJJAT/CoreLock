package com.privacyguard.app.ui.apps

import com.privacyguard.app.core.geoip.GeoIpResolver
import com.privacyguard.app.core.tracker.DestinationOwner
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsQueryEntity
import com.privacyguard.core.metadata.EncryptionStatus

/** One destination's path from the app to the server, built from recorded traffic. */
data class RoutePath(
    val destination: String,
    val ownerSummary: String,
    val connectionCount: Int,
    val hops: List<TopologyHop>,
)

/**
 * Builds the Topology tab from what was actually recorded — connections (with
 * measured connect time and encryption) and DNS queries (with measured resolver
 * time, answer and block decision). Nothing is estimated: a value that was not
 * measured is shown as not measured.
 */
object TopologyBuilder {

    fun build(
        packageName: String,
        appLabel: String,
        resolverLabel: String,
        connections: List<ConnectionEntity>,
        dnsQueries: List<DnsQueryEntity>,
        maxRoutes: Int = 8,
    ): List<RoutePath> {
        val queriesByDomain = dnsQueries.groupBy { it.domain.lowercase() }
        return connections
            .groupBy { hostOf(it) }
            .entries
            .sortedByDescending { it.value.size }
            .take(maxRoutes)
            .map { (host, conns) ->
                val ip = conns.first().destinationIp
                val owner = DestinationOwner.describe(host.takeIf { it != ip }, ip, packageName)
                RoutePath(
                    destination = host,
                    ownerSummary = owner.summary,
                    connectionCount = conns.size,
                    hops = listOf(
                        appHop(packageName, appLabel, conns),
                        policyHop(host, conns, queriesByDomain[host.lowercase()].orEmpty()),
                        dnsHop(host, ip, resolverLabel, queriesByDomain[host.lowercase()].orEmpty()),
                        serverHop(owner, conns),
                        encryptionHop(conns),
                    ),
                )
            }
    }

    /** Recent lookups with their real outcome and measured time. */
    fun queryLog(dnsQueries: List<DnsQueryEntity>, limit: Int = 10): List<TopologyQueryLogItem> =
        dnsQueries.take(limit).map { q ->
            TopologyQueryLogItem(
                domain = q.domain,
                status = when {
                    q.wasBlocked -> "BLOCKED"
                    q.responseMs < 0 -> "FAILED"
                    else -> "OK"
                },
                latencyLabel = if (q.responseMs > 0) "${q.responseMs}ms" else "--",
            )
        }

    private fun hostOf(c: ConnectionEntity): String =
        (c.sniHostname ?: c.domain)?.takeIf { it.isNotBlank() } ?: c.destinationIp

    private fun appHop(packageName: String, appLabel: String, conns: List<ConnectionEntity>): TopologyHop {
        val background = conns.count { it.wasBackground }
        return TopologyHop(
            label = appLabel,
            detail = buildString {
                append("$packageName · ${conns.size} connection${if (conns.size == 1) "" else "s"}")
                if (background > 0) append(" · $background while in background")
            },
            latencyLabel = "",
            state = TopologyState.SAFE,
            badge = "Phone",
        )
    }

    private fun policyHop(host: String, conns: List<ConnectionEntity>, queries: List<DnsQueryEntity>): TopologyHop {
        val blockedLookups = queries.count { it.wasBlocked }
        val blockedConns = conns.count { it.wasBlocked }
        val blocked = blockedLookups + blockedConns
        val attempts = queries.size + conns.size
        return when {
            blocked == 0 -> TopologyHop(
                label = "PrivacyGuard — allowed",
                detail = "No blocklist entry or firewall rule matches $host",
                latencyLabel = "",
                state = TopologyState.INTERCEPT,
                badge = "ALLOWED",
            )
            blocked >= attempts -> TopologyHop(
                label = "PrivacyGuard — blocked",
                detail = "Every attempt was stopped ($blockedLookups lookups, $blockedConns connections)",
                latencyLabel = "",
                state = TopologyState.BLOCKED,
                badge = "BLOCKED",
            )
            else -> TopologyHop(
                label = "PrivacyGuard — partly blocked",
                detail = "$blocked of $attempts attempts stopped by a blocklist or rule",
                latencyLabel = "",
                state = TopologyState.BLOCKED,
                badge = "PARTLY",
            )
        }
    }

    private fun dnsHop(host: String, ip: String, resolverLabel: String, queries: List<DnsQueryEntity>): TopologyHop {
        if (host == ip) {
            return TopologyHop(
                label = "No DNS lookup",
                detail = "The app connected to $ip directly — no hostname was looked up or sent",
                latencyLabel = "",
                state = TopologyState.NEUTRAL,
                badge = "DNS",
            )
        }
        if (queries.isEmpty()) {
            return TopologyHop(
                label = "DNS — $resolverLabel",
                detail = "$host → $ip · no lookup in this period (the address was already cached)",
                latencyLabel = "",
                state = TopologyState.RESOLVER,
                badge = "DNS",
            )
        }
        val timed = queries.map { it.responseMs }.filter { it > 0 }
        val failed = queries.count { it.responseMs < 0 }
        val answer = queries.firstNotNullOfOrNull { it.answerIp } ?: ip
        return TopologyHop(
            label = "DNS — $resolverLabel",
            detail = buildString {
                append("$host → $answer · ${queries.size} lookup${if (queries.size == 1) "" else "s"}")
                val idle = queries.count { it.phoneWasIdle }
                if (idle > 0) append(", $idle while the screen was off")
                if (failed > 0) append(" · $failed got no answer")
            },
            latencyLabel = median(timed)?.let { "${it}ms" } ?: "",
            state = TopologyState.RESOLVER,
            badge = "DNS",
        )
    }

    private fun serverHop(owner: DestinationOwner.Info, conns: List<ConnectionEntity>): TopologyHop {
        val first = conns.first()
        val geo = GeoIpResolver.lookup(first.destinationIp)
        val ports = conns.map { "${it.protocol} ${it.destinationPort}" }.distinct().take(3).joinToString(", ")
        val connect = median(conns.map { it.connectMs }.filter { it > 0 })
        return TopologyHop(
            label = owner.summary,
            detail = buildString {
                append("${first.destinationIp} · $ports")
                append(" · ")
                append(geo?.let { "${it.countryName} (${it.org})" } ?: "location not in the on-device IP list")
            },
            latencyLabel = connect?.let { "${it}ms" } ?: "",
            state = if (owner.role == DestinationOwner.Role.TRACKER) TopologyState.BLOCKED else TopologyState.NEUTRAL,
            badge = owner.role.label,
        )
    }

    private fun encryptionHop(conns: List<ConnectionEntity>): TopologyHop {
        val status = conns.groupingBy { it.encryptionStatus }.eachCount().maxByOrNull { it.value }?.key
            ?: EncryptionStatus.UNKNOWN.name
        val udp = conns.all { it.protocol.equals("UDP", ignoreCase = true) }
        val (label, state) = when (status) {
            EncryptionStatus.TLS_1_3.name -> "Encrypted · TLS 1.3" to TopologyState.SAFE
            EncryptionStatus.TLS_1_2.name -> "Encrypted · TLS 1.2" to TopologyState.SAFE
            EncryptionStatus.TLS.name -> "Encrypted · TLS" to TopologyState.SAFE
            EncryptionStatus.QUIC.name -> "Encrypted · QUIC" to TopologyState.SAFE
            EncryptionStatus.WEAK_TLS.name -> "Outdated encryption (TLS 1.0/1.1)" to TopologyState.BLOCKED
            EncryptionStatus.CLEARTEXT.name -> "Not encrypted — readable on the network" to TopologyState.BLOCKED
            else -> (if (udp) "UDP — contents not inspected" else "Not TLS — the app's own protocol") to TopologyState.NEUTRAL
        }
        val sent = conns.sumOf { it.bytesSent }
        val received = conns.sumOf { it.bytesReceived }
        return TopologyHop(
            label = label,
            detail = "${formatBytes(sent)} sent · ${formatBytes(received)} received",
            latencyLabel = "",
            state = state,
            badge = if (state == TopologyState.SAFE) "SECURE" else if (state == TopologyState.BLOCKED) "RISK" else "OPAQUE",
        )
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
