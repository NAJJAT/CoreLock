package com.privacyguard.app.data.db

import androidx.room.*

@Dao
interface DnsAnomalyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(anomaly: DnsAnomalyEntity)

    @Query("SELECT * FROM dns_anomalies ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DnsAnomalyEntity>

    @Query("SELECT * FROM dns_anomalies WHERE packageName = :packageName ORDER BY timestamp DESC")
    suspend fun forPackage(packageName: String): List<DnsAnomalyEntity>

    @Query("SELECT COUNT(*) FROM dns_anomalies WHERE severity >= 5")
    suspend fun countHighSeverity(): Int

    @Query("DELETE FROM dns_anomalies WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM dns_anomalies")
    suspend fun deleteAll()
}