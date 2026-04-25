package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.DnsAnomalyDao
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.vpn.inspector.DnsAnomalyDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DnsAnomalyRepo(private val dnsAnomalyDao: DnsAnomalyDao) {

    suspend fun save(anomaly: DnsAnomalyDetector.Anomaly) = withContext(Dispatchers.IO) {
        dnsAnomalyDao.insert(DnsAnomalyEntity(
            timestamp = anomaly.timestamp,
            packageName = anomaly.packageName,
            domain = anomaly.domain,
            anomalyType = anomaly.type.name,
            description = anomaly.description,
            severity = anomaly.severity,
        ))
    }

    suspend fun recent(limit: Int = 100): List<DnsAnomalyDetector.Anomaly> = withContext(Dispatchers.IO) {
        dnsAnomalyDao.recent(limit).map { it.toAnomaly() }
    }

    suspend fun forPackage(pkg: String): List<DnsAnomalyDetector.Anomaly> = withContext(Dispatchers.IO) {
        dnsAnomalyDao.forPackage(pkg).map { it.toAnomaly() }
    }

    suspend fun highSeverityCount(): Int = withContext(Dispatchers.IO) {
        dnsAnomalyDao.countHighSeverity()
    }

    suspend fun pruneOld(days: Int = 7) = withContext(Dispatchers.IO) {
        dnsAnomalyDao.deleteOlderThan(System.currentTimeMillis() - days.toLong() * 86_400_000)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dnsAnomalyDao.deleteAll()
    }

    private fun DnsAnomalyEntity.toAnomaly() = DnsAnomalyDetector.Anomaly(
        timestamp = timestamp,
        packageName = packageName,
        domain = domain,
        type = DnsAnomalyDetector.AnomalyType.valueOf(anomalyType),
        description = description,
        severity = severity,
    )
}