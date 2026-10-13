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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(connections: List<ConnectionEntity>)
    
    /**
     * Gets recent connections (last 24 hours)
     */
    @Query("SELECT * FROM connections WHERE timestamp > :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentConnections(since: Long, limit: Int = 500): List<ConnectionEntity>

    /** Background connections per app since [since] (Rules suggestions). */
    @Query("SELECT packageName, COUNT(*) AS count FROM connections WHERE timestamp > :since AND wasBackground = 1 AND packageName != '' GROUP BY packageName")
    suspend fun backgroundCountsSince(since: Long): List<PackageCount>
    
    /**
     * Gets connections for a specific app
     */
    @Query("SELECT * FROM connections WHERE appUid = :uid ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getConnectionsForApp(uid: Int, limit: Int = 100): List<ConnectionEntity>

    /**
     * Gets distinct domains (or IPs) contacted by a specific app, with stats
     */
    @Query("""
        SELECT
            COALESCE(sniHostname, domain, destinationIp) AS domain,
            COUNT(*)                        AS count
        FROM connections
        WHERE packageName = :packageName
        GROUP BY COALESCE(sniHostname, domain, destinationIp)
        ORDER BY count DESC
        LIMIT 100
    """)
    suspend fun getDomainsForPackage(packageName: String): List<TopBlockedDomain>

    @Query("""
        SELECT * FROM connections
        WHERE packageName = :packageName
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getRecentConnectionsForPackage(packageName: String, limit: Int = 40): List<ConnectionEntity>
    
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
     * Background connection count — apps that connected while not in the foreground.
     */
    @Query("SELECT COUNT(*) FROM connections WHERE wasBackground = 1 AND timestamp > :since")
    suspend fun getBackgroundConnectionCount(since: Long): Int

    /**
     * Top background-connecting apps: packages sorted by background connection count.
     */
    @Query("""
        SELECT packageName AS domain, COUNT(*) as count
        FROM connections
        WHERE wasBackground = 1 AND timestamp > :since AND packageName != ''
        GROUP BY packageName
        ORDER BY count DESC
        LIMIT :limit
    """)
    suspend fun getTopBackgroundApps(since: Long, limit: Int = 5): List<TopBlockedDomain>

    /**
     * Cleartext (unencrypted) connection count since [since].
     */
    @Query("""
        SELECT COUNT(*) FROM connections
        WHERE encryptionStatus = 'CLEARTEXT' AND wasBlocked = 0 AND timestamp > :since
    """)
    suspend fun getCleartextConnectionCount(since: Long): Int

    /**
     * Deletes old connections (older than specified days)
     */
    @Query("DELETE FROM connections WHERE timestamp < :cutoff")
    suspend fun deleteOldConnections(cutoff: Long)

    /**
     * Deletes at most [limit] rows older than [cutoff]. Callers loop until it returns
     * less than [limit], so no single transaction holds the write lock for long.
     */
    @Query("""
        DELETE FROM connections WHERE id IN
            (SELECT id FROM connections WHERE timestamp < :cutoff LIMIT :limit)
    """)
    suspend fun deleteOlderThanBatch(cutoff: Long, limit: Int): Int

    /** Timestamp of the [keep]th newest row, or null if there are fewer than [keep] rows. */
    @Query("SELECT timestamp FROM connections ORDER BY timestamp DESC LIMIT 1 OFFSET :keep - 1")
    suspend fun capCutoff(keep: Int): Long?
    
    /**
     * Deletes all connections
     */
    @Query("DELETE FROM connections")
    suspend fun deleteAll()
    
    // ── Drill-down lists. Every Home number is the size of one of these lists,
    //    so the number and the list it opens can never disagree. ─────────────────

    @Query("SELECT * FROM connections WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun allSince(since: Long): List<ConnectionEntity>

    /** Connections that went through (blocked attempts are listed by [blockedBetween]). */
    @Query("SELECT * FROM connections WHERE timestamp > :from AND timestamp <= :to AND wasBlocked = 0 ORDER BY timestamp DESC")
    suspend fun between(from: Long, to: Long): List<ConnectionEntity>

    @Query("SELECT COUNT(*) FROM connections WHERE timestamp > :from AND timestamp <= :to AND wasBlocked = 0")
    suspend fun countBetween(from: Long, to: Long): Int

    /** Connection attempts PrivacyGuard dropped (rules, DNS-bypass guard, cleartext policy). */
    @Query("SELECT * FROM connections WHERE timestamp > :from AND timestamp <= :to AND wasBlocked = 1 ORDER BY timestamp DESC")
    suspend fun blockedBetween(from: Long, to: Long): List<ConnectionEntity>

    @Query("SELECT COUNT(*) FROM connections WHERE timestamp > :from AND timestamp <= :to AND wasBlocked = 1")
    suspend fun countBlockedBetween(from: Long, to: Long): Int

    /** Distinct destinations of blocked attempts: SNI, else DNS name, else address. */
    @Query("SELECT DISTINCT COALESCE(sniHostname, domain, destinationIp) FROM connections WHERE timestamp > :from AND timestamp <= :to AND wasBlocked = 1")
    suspend fun blockedHostsBetween(from: Long, to: Long): List<String>

    @Query("SELECT COALESCE(SUM(bytesSent + bytesReceived), 0) FROM connections WHERE timestamp > :from AND timestamp <= :to")
    suspend fun bytesBetween(from: Long, to: Long): Long

    @Query("""
        SELECT * FROM connections
        WHERE packageName = :packageName AND timestamp >= :from AND timestamp <= :to
        ORDER BY timestamp DESC
    """)
    suspend fun forPackageBetween(packageName: String, from: Long, to: Long): List<ConnectionEntity>

    @Query("""
        SELECT * FROM connections
        WHERE (domain = :host OR sniHostname = :host) AND timestamp > :since
        ORDER BY timestamp DESC
    """)
    suspend fun forHostSince(host: String, since: Long): List<ConnectionEntity>

    @Query("SELECT MIN(timestamp) FROM connections WHERE domain = :host OR sniHostname = :host")
    suspend fun firstSeen(host: String): Long?

    @Query("""
        SELECT * FROM connections
        WHERE encryptionStatus = 'CLEARTEXT' AND wasBlocked = 0 AND timestamp > :since
        ORDER BY timestamp DESC
    """)
    suspend fun cleartextSince(since: Long): List<ConnectionEntity>

    /**
     * Gets rule count
     */
    @Query("SELECT COUNT(*) FROM connections")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM connections WHERE timestamp > :since")
    suspend fun getCountSince(since: Long): Int
    
    /**
     * Gets hourly statistics for charts
     */
    @Query("""
        SELECT 
            strftime('%H', datetime(timestamp/1000, 'unixepoch', 'localtime')) as hour,
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
            strftime('%Y-%m-%d', datetime(timestamp/1000, 'unixepoch', 'localtime')) as date,
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


data class PackageCount(val packageName: String, val count: Int)
