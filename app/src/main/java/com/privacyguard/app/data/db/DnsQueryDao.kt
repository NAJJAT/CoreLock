package com.privacyguard.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

data class DnsAppSummary(
    val appPackage: String,
    val appName: String,
    val totalQueries: Int,
    val idleQueries: Int,
    val blockedQueries: Int,
)

data class DnsDomainSummary(
    val domain: String,
    val totalQueries: Int,
    val idleQueries: Int,
    val blockedQueries: Int,
)

data class DnsHourBucket(
    val hour: Int,
    val totalQueries: Int,
    val idleQueries: Int,
    val blockedQueries: Int,
)

@Dao
interface DnsQueryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(query: DnsQueryEntity): Long

    @Query("SELECT COUNT(*) FROM dns_queries WHERE timestamp >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM dns_queries WHERE timestamp >= :since AND phone_was_idle = 1")
    suspend fun countIdleSince(since: Long): Int

    @Query("SELECT COUNT(DISTINCT app_package) FROM dns_queries WHERE timestamp >= :since AND phone_was_idle = 1")
    suspend fun countIdleAppsSince(since: Long): Int

    @Query("SELECT COUNT(DISTINCT domain) FROM dns_queries WHERE timestamp >= :since AND phone_was_idle = 1 AND was_blocked = 1")
    suspend fun countIdleBlockedDomainsSince(since: Long): Int

    @Query("""
        SELECT app_package AS appPackage,
               app_name AS appName,
               COUNT(*) AS totalQueries,
               SUM(CASE WHEN phone_was_idle THEN 1 ELSE 0 END) AS idleQueries,
               SUM(CASE WHEN was_blocked THEN 1 ELSE 0 END) AS blockedQueries
        FROM dns_queries
        WHERE timestamp >= :since
        GROUP BY app_package, app_name
        ORDER BY totalQueries DESC
        LIMIT :limit
    """)
    suspend fun appSummariesSince(since: Long, limit: Int): List<DnsAppSummary>

    @Query("""
        SELECT app_package AS appPackage,
               app_name AS appName,
               COUNT(*) AS totalQueries,
               SUM(CASE WHEN phone_was_idle THEN 1 ELSE 0 END) AS idleQueries,
               SUM(CASE WHEN was_blocked THEN 1 ELSE 0 END) AS blockedQueries
        FROM dns_queries
        WHERE timestamp >= :since AND phone_was_idle = 1
        GROUP BY app_package, app_name
        ORDER BY idleQueries DESC
        LIMIT :limit
    """)
    suspend fun idleAppSummariesSince(since: Long, limit: Int): List<DnsAppSummary>

    @Query("""
        SELECT domain AS domain,
               COUNT(*) AS totalQueries,
               SUM(CASE WHEN phone_was_idle THEN 1 ELSE 0 END) AS idleQueries,
               SUM(CASE WHEN was_blocked THEN 1 ELSE 0 END) AS blockedQueries
        FROM dns_queries
        WHERE timestamp >= :since AND app_package = :packageName
        GROUP BY domain
        ORDER BY totalQueries DESC
        LIMIT :limit
    """)
    suspend fun topDomainsForApp(packageName: String, since: Long, limit: Int): List<DnsDomainSummary>

    @Query("""
        SELECT domain AS domain,
               COUNT(*) AS totalQueries,
               SUM(CASE WHEN phone_was_idle THEN 1 ELSE 0 END) AS idleQueries,
               SUM(CASE WHEN was_blocked THEN 1 ELSE 0 END) AS blockedQueries
        FROM dns_queries
        WHERE timestamp >= :since AND phone_was_idle = 1
        GROUP BY domain
        ORDER BY idleQueries DESC
        LIMIT :limit
    """)
    suspend fun topIdleDomainsSince(since: Long, limit: Int): List<DnsDomainSummary>

    @Query("""
        SELECT CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
               COUNT(*) AS totalQueries,
               SUM(CASE WHEN phone_was_idle THEN 1 ELSE 0 END) AS idleQueries,
               SUM(CASE WHEN was_blocked THEN 1 ELSE 0 END) AS blockedQueries
        FROM dns_queries
        WHERE timestamp >= :since AND app_package = :packageName
        GROUP BY hour
        ORDER BY hour
    """)
    suspend fun hourlyForApp(packageName: String, since: Long): List<DnsHourBucket>

    @Query("""
        SELECT COUNT(*) FROM dns_queries
        WHERE timestamp >= :since AND app_package = :packageName
    """)
    suspend fun countForApp(packageName: String, since: Long): Int

    @Query("""
        SELECT COUNT(*) FROM dns_queries
        WHERE timestamp >= :since AND app_package = :packageName AND phone_was_idle = 1
    """)
    suspend fun idleCountForApp(packageName: String, since: Long): Int

    @Query("DELETE FROM dns_queries WHERE timestamp < :cutoff")
    suspend fun pruneOld(cutoff: Long)
}

