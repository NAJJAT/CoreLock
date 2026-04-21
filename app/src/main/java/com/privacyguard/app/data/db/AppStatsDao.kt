/**
 * AppStatsDao.kt
 * 
 * Data Access Object for application statistics
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.data.db

import androidx.room.*

@Dao
interface AppStatsDao {
    
    /**
     * Inserts or updates app stats for a day
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: AppStatsEntity)
    
    /**
     * Gets stats for an app for a specific date
     */
    @Query("SELECT * FROM app_stats WHERE appUid = :uid AND date = :date")
    suspend fun getStatsForApp(uid: Int, date: String): AppStatsEntity?
    
    /**
     * Gets stats for an app for the last N days
     */
    @Query("SELECT * FROM app_stats WHERE appUid = :uid ORDER BY date DESC LIMIT :days")
    suspend fun getStatsForAppLastDays(uid: Int, days: Int): List<AppStatsEntity>
    
    /**
     * Gets all apps with stats for today
     */
    @Query("SELECT * FROM app_stats WHERE date = :today ORDER BY bytesSent + bytesReceived DESC")
    suspend fun getTodayStats(today: String): List<AppStatsEntity>
    
    /**
     * Gets top data-using apps for a date range
     */
    @Query("""
        SELECT 
            appUid, 
            appName, 
            SUM(bytesSent) as totalBytesSent,
            SUM(bytesReceived) as totalBytesReceived,
            SUM(blockedCount) as totalBlocked
        FROM app_stats 
        WHERE date >= :startDate AND date <= :endDate
        GROUP BY appUid
        ORDER BY totalBytesSent + totalBytesReceived DESC
        LIMIT :limit
    """)
    suspend fun getTopApps(startDate: String, endDate: String, limit: Int = 10): List<TopAppStats>
    
    /**
     * Updates stats for a connection
     */
    @Query("""
        UPDATE app_stats 
        SET bytesSent = bytesSent + :sent,
            bytesReceived = bytesReceived + :received,
            packetsSent = packetsSent + :packetsSent,
            packetsReceived = packetsReceived + :packetsReceived
        WHERE appUid = :uid AND date = :date
    """)
    suspend fun updateConnectionStats(
        uid: Int,
        date: String,
        sent: Long,
        received: Long,
        packetsSent: Int,
        packetsReceived: Int
    )
    
    /**
     * Increments blocked count for an app
     */
    @Query("""
        UPDATE app_stats 
        SET blockedCount = blockedCount + 1
        WHERE appUid = :uid AND date = :date
    """)
    suspend fun incrementBlockedCount(uid: Int, date: String)
    
    /**
     * Deletes old stats
     */
    @Query("DELETE FROM app_stats WHERE date < :cutoff")
    suspend fun deleteOldStats(cutoff: String)
}

data class TopAppStats(
    val appUid: Int,
    val appName: String,
    val totalBytesSent: Long,
    val totalBytesReceived: Long,
    val totalBlocked: Long
)