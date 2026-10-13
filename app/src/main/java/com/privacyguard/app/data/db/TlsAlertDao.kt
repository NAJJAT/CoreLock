package com.privacyguard.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TlsAlertDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRow(alert: TlsAlertEntity)

    @Query("""
        SELECT COUNT(*) FROM tls_alerts
        WHERE alertType = :type AND IFNULL(packageName, '') = :pkg AND IFNULL(sni, '') = :sni AND timestamp > :since
    """)
    suspend fun countSimilarSince(type: String, pkg: String, sni: String, since: Long): Int

    /**
     * One alert per type, app and host per day: a weak cipher used to be stored on
     * every handshake, burying everything else in the inbox. Returns whether stored.
     */
    @Transaction
    suspend fun insert(alert: TlsAlertEntity): Boolean {
        val since = alert.timestamp - ALERT_DEDUP_MS
        if (countSimilarSince(alert.alertType, alert.packageName.orEmpty(), alert.sni.orEmpty(), since) > 0) return false
        insertRow(alert)
        return true
    }

    companion object {
        const val ALERT_DEDUP_MS = 24L * 60L * 60L * 1000L
    }

    @Query("SELECT * FROM tls_alerts ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TlsAlertEntity>

    @Query("SELECT * FROM tls_alerts ORDER BY timestamp DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<TlsAlertEntity>>

    /** Every stored alert per type; the screen used to count only the newest 200 rows. */
    @Query("SELECT alertType, COUNT(*) AS count FROM tls_alerts GROUP BY alertType")
    fun countsByTypeFlow(): Flow<List<AlertTypeCount>>

    @Query("SELECT * FROM tls_alerts WHERE alertType = :type ORDER BY timestamp DESC LIMIT :limit")
    fun recentOfTypeFlow(type: String, limit: Int): Flow<List<TlsAlertEntity>>

    @Query("SELECT COUNT(*) FROM tls_alerts WHERE alertType = 'JA3_THREAT'")
    fun ja3ThreatCountFlow(): Flow<Int>

    @Query("SELECT * FROM tls_alerts WHERE alertType = 'JA3_THREAT' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentJa3Threats(limit: Int): List<TlsAlertEntity>

    @Query("SELECT * FROM tls_alerts WHERE alertType = 'WEAK_CIPHER' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentCipherAlerts(limit: Int): List<TlsAlertEntity>

    @Query("SELECT * FROM tls_alerts WHERE alertType = 'CT_NEW_CERT' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentCtAlerts(limit: Int): List<TlsAlertEntity>

    @Query("SELECT COUNT(*) FROM tls_alerts WHERE alertType = 'JA3_THREAT'")
    suspend fun countJa3Threats(): Int

    @Query("SELECT COUNT(*) FROM tls_alerts WHERE alertType = 'WEAK_CIPHER'")
    suspend fun countWeakCipher(): Int

    @Query("SELECT * FROM tls_alerts WHERE severity >= :minSeverity ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentHighSeverity(minSeverity: Int, limit: Int): List<TlsAlertEntity>

    @Query("SELECT * FROM tls_alerts WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun since(since: Long): List<TlsAlertEntity>

    @Query("DELETE FROM tls_alerts WHERE timestamp < :before")
    suspend fun pruneOld(before: Long)
}


data class AlertTypeCount(val alertType: String, val count: Int)
