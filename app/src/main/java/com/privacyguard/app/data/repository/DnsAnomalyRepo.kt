package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.DnsAnomalyDao
import com.privacyguard.app.data.db.DnsAnomalyEntity
import com.privacyguard.vpn.inspector.DnsAnomalyDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DnsAnomalyRepo(private val dnsAnomalyDao: DnsAnomalyDao) {

    /** False when the same app and host already has an anomaly today (see DnsAnomalyDao.insert). */
    suspend fun save(anomaly: DnsAnomalyDetector.Anomaly, networkKey: String? = null): Boolean = withContext(Dispatchers.IO) {
        dnsAnomalyDao.insert(DnsAnomalyEntity(
            timestamp = anomaly.timestamp,
            packageName = anomaly.packageName,
            domain = com.privacyguard.core.utils.Hostnames.display(anomaly.domain),
            anomalyType = anomaly.type.name,
            description = com.privacyguard.core.utils.Hostnames.label(anomaly.description, max = 500),
            severity = anomaly.severity,
            networkKey = networkKey,
        ), dedupeBase = DnsAnomalyDetector.baseForDedupe(anomaly))
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