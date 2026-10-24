package com.privacyguard.app.data.db

import androidx.room.*

@Dao
interface DnsAnomalyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRow(anomaly: DnsAnomalyEntity)

    @Query("SELECT COUNT(*) FROM dns_anomalies WHERE packageName = :pkg AND domain = :domain AND timestamp > :since")
    suspend fun countForSince(pkg: String, domain: String, since: Long): Int

    /**
     * One anomaly per app and host per day, whatever its type, so the detector's
     * in-memory cooldown also holds across VPN restarts. Returns whether stored.
     */
    @Query("""
        SELECT COUNT(*) FROM dns_anomalies
        WHERE packageName = :pkg AND (domain = :base OR domain LIKE '%.' || :base) AND timestamp > :since
    """)
    suspend fun countForBaseSince(pkg: String, base: String, since: Long): Int

    @Query("SELECT COUNT(*) FROM dns_anomalies WHERE packageName = :pkg AND anomalyType = :type AND timestamp > :since")
    suspend fun countTypeForSince(pkg: String, type: String, since: Long): Int

    /**
     * [perApp]: once per app and type, whatever the host (a burst of lookups is about the
     * app; per host, one page load stored an alert for each of its ad hosts).
     */
    @Transaction
    suspend fun insert(anomaly: DnsAnomalyEntity, dedupeBase: String? = null, perApp: Boolean = false): Boolean {
        val since = anomaly.timestamp - TlsAlertDao.ALERT_DEDUP_MS
        // Name-shape findings: once per app and base domain (their hosts are all unique).
        val seen = when {
            perApp -> countTypeForSince(anomaly.packageName, anomaly.anomalyType, since)
            dedupeBase != null -> countForBaseSince(anomaly.packageName, dedupeBase, since)
            else -> countForSince(anomaly.packageName, anomaly.domain, since)
        }
        if (seen > 0) return false
        insertRow(anomaly)
        return true
    }

    @Query("SELECT * FROM dns_anomalies ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DnsAnomalyEntity>

    @Query("SELECT * FROM dns_anomalies WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun since(since: Long): List<DnsAnomalyEntity>

    @Query("SELECT * FROM dns_anomalies WHERE domain = :domain ORDER BY timestamp DESC")
    suspend fun forDomain(domain: String): List<DnsAnomalyEntity>

    @Query("SELECT * FROM dns_anomalies WHERE packageName = :packageName ORDER BY timestamp DESC")
    suspend fun forPackage(packageName: String): List<DnsAnomalyEntity>

    @Query("SELECT COUNT(*) FROM dns_anomalies WHERE severity >= 5")
    suspend fun countHighSeverity(): Int

    @Query("DELETE FROM dns_anomalies WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM dns_anomalies")
    suspend fun deleteAll()
}