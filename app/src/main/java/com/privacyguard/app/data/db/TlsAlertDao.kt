package com.privacyguard.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TlsAlertDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: TlsAlertEntity)

    @Query("SELECT * FROM tls_alerts ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TlsAlertEntity>

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

    @Query("SELECT COUNT(*) FROM tls_alerts WHERE severity >= :minSeverity ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentHighSeverity(minSeverity: Int, limit: Int): List<TlsAlertEntity>

    @Query("DELETE FROM tls_alerts WHERE timestamp < :before")
    suspend fun pruneOld(before: Long)
}
