package com.privacyguard.data.repository

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.*
import com.privacyguard.core.session.SessionSnapshot
import com.privacyguard.data.db.*
import com.privacyguard.vpn.inspector.DnsAnomalyDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ══════════════════════════════════════════════════════════════════════════════
// ConnectionRepo
// ══════════════════════════════════════════════════════════════════════════════

class ConnectionRepo(
    private val dao: ConnectionDao,
    private val retentionDays: Int = 30,
) {
    suspend fun save(snapshot: SessionSnapshot, wasBlocked: Boolean, matchedRuleId: String?) =
        withContext(Dispatchers.IO) {
            dao.insert(snapshot.toEntity(wasBlocked, matchedRuleId))
        }

    suspend fun recent(limit: Int = 200)        = withContext(Dispatchers.IO) { dao.recent(limit) }
    suspend fun forPackage(pkg: String)          = withContext(Dispatchers.IO) { dao.forPackage(pkg) }
    suspend fun recentBlocked(limit: Int = 100)  = withContext(Dispatchers.IO) { dao.recentBlocked(limit) }
    suspend fun cleartextConnections()           = withContext(Dispatchers.IO) { dao.cleartextConnections() }
    suspend fun weakTlsConnections()             = withContext(Dispatchers.IO) { dao.weakTlsConnections() }
    suspend fun search(q: String)                = withContext(Dispatchers.IO) { dao.search(q) }
    suspend fun blockedCount()                   = withContext(Dispatchers.IO) { dao.blockedCount() }
    suspend fun cleartextCount()                 = withContext(Dispatchers.IO) { dao.cleartextCount() }
    suspend fun totalBytes()                     = withContext(Dispatchers.IO) { dao.totalBytes() ?: 0L }
    suspend fun topBlockedApps(limit: Int = 10)  = withContext(Dispatchers.IO) { dao.topBlockedApps(limit) }

    suspend fun weeklyStats(): WeeklyStats = withContext(Dispatchers.IO) {
        WeeklyStats(
            totalConnections      = dao.blockedCount() + /* approx */ dao.totalBytes().let { 0L },
            blockedConnections    = dao.blockedCount(),
            cleartextConnections  = dao.cleartextCount(),
            topOffenders          = dao.topBlockedApps(5),
        )
    }

    suspend fun pruneOldRecords(): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - retentionDays.toLong() * 86_400_000
        dao.deleteOlderThan(cutoff)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAll() }

    private fun SessionSnapshot.toEntity(wasBlocked: Boolean, ruleId: String?) =
        ConnectionEntity(
            timestamp        = createdAt,
            protocol         = key.protocol,
            sourceIp         = key.sourceIp,
            sourcePort       = key.sourcePort,
            destinationIp    = key.destinationIp,
            destinationPort  = key.destinationPort,
            hostname         = hostname,
            ownerUid         = ownerUid,
            ownerPackage     = ownerPackage,
            bytesOut         = bytesFromDevice,
            bytesIn          = bytesToDevice,
            wasBlocked       = wasBlocked,
            matchedRuleId    = ruleId,
            durationMs       = ageMs,
            encryptionStatus = encryptionStatus.name,
            tlsVersion       = tlsVersion?.name,
            sniHostname      = tlsSni,
            wasBackground    = wasBackground,
        )

    data class WeeklyStats(
        val totalConnections:     Long,
        val blockedConnections:   Long,
        val cleartextConnections: Long,
        val topOffenders:         List<AppBlockCount>,
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// RulesRepo
// ══════════════════════════════════════════════════════════════════════════════

class RulesRepo(
    private val dao:          RulesDao,
    private val filterEngine: FilterEngine,
) {
    suspend fun loadIntoEngine() = withContext(Dispatchers.IO) {
        filterEngine.setRules(dao.enabledRules().map { it.toDomain() })
    }

    suspend fun addRule(rule: FilterRule)    = withContext(Dispatchers.IO) { dao.insert(rule.toEntity()); filterEngine.addRule(rule) }
    suspend fun deleteRule(id: String)       = withContext(Dispatchers.IO) { dao.deleteById(id);          filterEngine.removeRule(id) }
    suspend fun setEnabled(id: String, on: Boolean) = withContext(Dispatchers.IO) { dao.setEnabled(id, on); filterEngine.setEnabled(id, on) }

    suspend fun allRules()     = withContext(Dispatchers.IO) { dao.allRules().map { it.toDomain() } }
    suspend fun userRules()    = withContext(Dispatchers.IO) { dao.bySource("USER").map { it.toDomain() } }
    suspend fun activeDenyCount() = withContext(Dispatchers.IO) { dao.activeDenyCount() }

    private fun FilterRule.toEntity() = RuleEntity(
        id              = id, label = label, action = action.name, source = source.name,
        priority        = priority, isEnabled = isEnabled,
        matchUid        = matchUid, matchPackage = matchPackage, matchDomain = matchDomain,
        matchIp         = matchIp, matchPort = matchPort, matchProtocol = matchProtocol?.name,
        matchEncryption = matchEncryption?.name,
        createdAt       = System.currentTimeMillis(),
    )

    private fun RuleEntity.toDomain() = FilterRule(
        id              = id, label = label,
        action          = FilterRule.Action.valueOf(action),
        source          = FilterRule.Source.valueOf(source),
        priority        = priority, isEnabled = isEnabled,
        matchUid        = matchUid, matchPackage = matchPackage, matchDomain = matchDomain,
        matchIp         = matchIp, matchPort = matchPort,
        matchProtocol   = matchProtocol?.let { FilterRule.Protocol.valueOf(it) },
        matchEncryption = matchEncryption?.let {
            com.privacyguard.core.metadata.EncryptionStatus.valueOf(it)
        },
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// BlocklistRepo
// ══════════════════════════════════════════════════════════════════════════════

class BlocklistRepo(private val dao: BlocklistDao) {
    suspend fun replaceSource(source: String, category: String, domains: List<String>) =
        withContext(Dispatchers.IO) {
            dao.deleteBySource(source)
            val now = System.currentTimeMillis()
            dao.insertAll(domains.map { BlocklistEntity(domain = it.lowercase().trim(), category = category, listSource = source, updatedAt = now) })
        }

    suspend fun allDomains()                    = withContext(Dispatchers.IO) { dao.allDomains() }
    suspend fun domainsForCategory(cat: String) = withContext(Dispatchers.IO) { dao.domainsForCategory(cat) }
    suspend fun totalCount()                    = withContext(Dispatchers.IO) { dao.count() }
    suspend fun deleteAll()                     = withContext(Dispatchers.IO) { dao.deleteAll() }
    suspend fun deleteBySource(src: String)     = withContext(Dispatchers.IO) { dao.deleteBySource(src) }

    companion object {
        const val SOURCE_EASYLIST    = "EasyList"
        const val SOURCE_STEVEN_BLACK = "StevenBlack"
        const val SOURCE_OISD        = "OISD"
        const val CAT_ADS            = "ads"
        const val CAT_TRACKERS       = "trackers"
        const val CAT_MALWARE        = "malware"
        const val CAT_TELEMETRY      = "telemetry"
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// ★ MetadataRepo
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Persists behavioral profiles produced by [MetadataEngine] to the database.
 * Allows the UI to show historical patterns even after app restart.
 */
class MetadataRepo(private val dao: ConnectionProfileDao) {

    suspend fun upsertProfile(profile: ConnectionProfile) = withContext(Dispatchers.IO) {
        dao.upsert(profile.toEntity())
    }

    suspend fun upsertAll(profiles: List<ConnectionProfile>) = withContext(Dispatchers.IO) {
        dao.upsertAll(profiles.map { it.toEntity() })
    }

    suspend fun allProfiles()                = withContext(Dispatchers.IO) { dao.allProfiles().map { it.toDomain() } }
    suspend fun profilesForApp(pkg: String)  = withContext(Dispatchers.IO) { dao.profilesForApp(pkg).map { it.toDomain() } }
    suspend fun riskyProfiles(min: Int = 40) = withContext(Dispatchers.IO) { dao.riskyProfiles(min).map { it.toDomain() } }
    suspend fun cleartextProfiles()          = withContext(Dispatchers.IO) { dao.cleartextProfiles().map { it.toDomain() } }
    suspend fun search(q: String)            = withContext(Dispatchers.IO) { dao.search(q).map { it.toDomain() } }
    suspend fun trackedPackages()            = withContext(Dispatchers.IO) { dao.trackedPackages() }
    suspend fun pruneOld(maxAgeMs: Long)     = withContext(Dispatchers.IO) { dao.deleteOlderThan(System.currentTimeMillis() - maxAgeMs) }

    // ── Mappers ──────────────────────────────────────────────────────────────

    private fun ConnectionProfile.toEntity() = ConnectionProfileEntity(
        packageName           = packageName, hostname = hostname,
        destinationIp         = destinationIp, destinationPort = destinationPort,
        connectionCount       = connectionCount, totalBytesOut = totalBytesOut, totalBytesIn = totalBytesIn,
        avgBytesPerConnection = avgBytesPerConnection, firstSeen = firstSeen, lastSeen = lastSeen,
        avgIntervalMs         = avgIntervalMs, minIntervalMs = minIntervalMs,
        backgroundRatio       = backgroundRatio,
        hourlyDistribution    = hourlyDistribution.joinToString(","),
        encryptionStatus      = encryptionStatus.name, tlsVersion = tlsVersion?.name, sniHostname = sniHostname,
        riskScore             = riskScore,
        riskSignalCodes       = riskSignals.joinToString(",") { it.code.name },
        updatedAt             = System.currentTimeMillis(),
    )

    private fun ConnectionProfileEntity.toDomain(): ConnectionProfile {
        val hours   = hourlyDistribution.split(",").map { it.toIntOrNull() ?: 0 }.toIntArray().let {
            if (it.size == 24) it else IntArray(24)
        }
        val encStat = runCatching { EncryptionStatus.valueOf(encryptionStatus) }.getOrDefault(EncryptionStatus.UNKNOWN)
        val tlsVer  = tlsVersion?.let { runCatching { TlsVersion.valueOf(it) }.getOrNull() }
        val signals = riskSignalCodes.split(",").filter { it.isNotEmpty() }.mapNotNull { code ->
            runCatching { RiskCode.valueOf(code) }.getOrNull()?.let { RiskSignal(it, code, 5) }
        }
        return ConnectionProfile(
            packageName           = packageName, hostname = hostname,
            destinationIp         = destinationIp, destinationPort = destinationPort,
            connectionCount       = connectionCount, totalBytesOut = totalBytesOut, totalBytesIn = totalBytesIn,
            avgBytesPerConnection = avgBytesPerConnection, firstSeen = firstSeen, lastSeen = lastSeen,
            avgIntervalMs         = avgIntervalMs, minIntervalMs = minIntervalMs,
            backgroundRatio       = backgroundRatio, hourlyDistribution = hours,
            encryptionStatus      = encStat, tlsVersion = tlsVer, sniHostname = sniHostname,
            riskScore             = riskScore, riskSignals = signals,
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// ★ DnsAnomalyRepo
// ══════════════════════════════════════════════════════════════════════════════

class DnsAnomalyRepo(private val dao: DnsAnomalyDao) {
    suspend fun save(anomaly: DnsAnomalyDetector.Anomaly) = withContext(Dispatchers.IO) {
        dao.insert(DnsAnomalyEntity(
            timestamp   = anomaly.timestamp, packageName = anomaly.packageName,
            domain      = anomaly.domain, anomalyType = anomaly.type.name,
            description = anomaly.description, severity = anomaly.severity,
        ))
    }

    suspend fun recent(limit: Int = 100)   = withContext(Dispatchers.IO) { dao.recent(limit) }
    suspend fun forPackage(pkg: String)    = withContext(Dispatchers.IO) { dao.forPackage(pkg) }
    suspend fun highSeverityCount()        = withContext(Dispatchers.IO) { dao.countHighSeverity() }
    suspend fun pruneOld(days: Int = 7)    = withContext(Dispatchers.IO) {
        dao.deleteOlderThan(System.currentTimeMillis() - days.toLong() * 86_400_000)
    }
    suspend fun deleteAll()                = withContext(Dispatchers.IO) { dao.deleteAll() }
}