/**
 * ConnectionDao.kt
 * 
 * Data Access Object for connection history
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.data.db

import androidx.room.*

@Dao
interface ConnectionDao {
    
    /**
     * Inserts a new connection record
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(connection: ConnectionEntity)
    
    /**
     * Gets recent connections (last 24 hours)
     */
    @Query("SELECT * FROM connections WHERE timestamp > :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentConnections(since: Long, limit: Int = 500): List<ConnectionEntity>
    
    /**
     * Gets connections for a specific app
     */
    @Query("SELECT * FROM connections WHERE appUid = :uid ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getConnectionsForApp(uid: Int, limit: Int = 100): List<ConnectionEntity>
    
    /**
     * Gets blocked connections count for today
     */
    @Query("SELECT COUNT(*) FROM connections WHERE wasBlocked = 1 AND timestamp > :since")
    suspend fun getBlockedCountToday(since: Long): Int
    
    /**
     * Gets total data usage for today
     */
    @Query("SELECT SUM(bytesSent + bytesReceived) FROM connections WHERE timestamp > :since")
    suspend fun getTotalDataToday(since: Long): Long
    
    /**
     * Gets top blocked domains
     */
    @Query("""
        SELECT domain, COUNT(*) as count 
        FROM connections 
        WHERE wasBlocked = 1 AND domain IS NOT NULL AND timestamp > :since 
        GROUP BY domain 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    suspend fun getTopBlockedDomains(since: Long, limit: Int = 10): List<TopBlockedDomain>
    
    /**
     * Deletes old connections (older than specified days)
     */
    @Query("DELETE FROM connections WHERE timestamp < :cutoff")
    suspend fun deleteOldConnections(cutoff: Long)
    
    /**
     * Gets hourly statistics for charts
     */
    @Query("""
        SELECT 
            strftime('%H', datetime(timestamp/1000, 'unixepoch')) as hour,
            COUNT(*) as totalConnections,
            SUM(CASE WHEN wasBlocked = 1 THEN 1 ELSE 0 END) as blockedConnections,
            SUM(bytesSent + bytesReceived) as totalBytes
        FROM connections 
        WHERE timestamp > :since 
        GROUP BY hour
        ORDER BY hour
    """)
    suspend fun getHourlyStats(since: Long): List<HourlyStats>
    
    /**
     * Gets daily statistics for the last N days
     */
    @Query("""
        SELECT 
            strftime('%Y-%m-%d', datetime(timestamp/1000, 'unixepoch')) as date,
            COUNT(*) as totalConnections,
            SUM(CASE WHEN wasBlocked = 1 THEN 1 ELSE 0 END) as blockedConnections,
            SUM(bytesSent + bytesReceived) as totalBytes
        FROM connections 
        WHERE timestamp > :since 
        GROUP BY date
        ORDER BY date DESC
    """)
    suspend fun getDailyStats(since: Long): List<DailyStats>
}

// ============================================================
// Result Classes
// ============================================================

data class TopBlockedDomain(
    val domain: String,
    val count: Int
)

data class HourlyStats(
    val hour: String,
    val totalConnections: Int,
    val blockedConnections: Int,
    val totalBytes: Long
)

data class DailyStats(
    val date: String,
    val totalConnections: Int,
    val blockedConnections: Int,
    val totalBytes: Long
)