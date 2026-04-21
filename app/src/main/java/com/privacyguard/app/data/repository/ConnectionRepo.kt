/**
 * ConnectionRepo.kt
 * 
 * Repository for connection history data
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.ConnectionDao
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DailyStats
import com.privacyguard.app.data.db.HourlyStats
import com.privacyguard.app.data.db.TopBlockedDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConnectionRepository(
    private val connectionDao: ConnectionDao
) {
    
    /**
     * Records a new connection
     */
    suspend fun recordConnection(
        appUid: Int,
        appName: String,
        destinationIp: String,
        destinationPort: Int,
        protocol: String,
        domain: String?,
        bytesSent: Long,
        bytesReceived: Long,
        wasBlocked: Boolean,
        blockReason: String?
    ) {
        val connection = ConnectionEntity(
            timestamp = System.currentTimeMillis(),
            appUid = appUid,
            appName = appName,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
            protocol = protocol,
            domain = domain,
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            wasBlocked = wasBlocked,
            blockReason = blockReason
        )
        connectionDao.insert(connection)
    }
    
    /**
     * Gets recent connections as Flow (for UI)
     */
    fun getRecentConnectionsFlow(since: Long, limit: Int = 500): Flow<List<ConnectionEntity>> {
        // Note: Room doesn't support suspend functions in Flow directly
        // This is a simplified version - in production, use LiveData or StateFlow
        return flow {
            emit(connectionDao.getRecentConnections(since, limit))
        }
    }
    
    /**
     * Gets blocked count for today
     */
    suspend fun getBlockedCountToday(): Int {
        val todayStart = getTodayStart()
        return connectionDao.getBlockedCountToday(todayStart)
    }
    
    /**
     * Gets total data usage for today
     */
    suspend fun getTotalDataToday(): Long {
        val todayStart = getTodayStart()
        return connectionDao.getTotalDataToday(todayStart)
    }
    
    /**
     * Gets top blocked domains
     */
    suspend fun getTopBlockedDomains(limit: Int = 10): List<TopBlockedDomain> {
        val weekStart = getWeekStart()
        return connectionDao.getTopBlockedDomains(weekStart, limit)
    }
    
    /**
     * Gets hourly statistics for charts
     */
    suspend fun getHourlyStats(): List<HourlyStats> {
        val todayStart = getTodayStart()
        return connectionDao.getHourlyStats(todayStart)
    }
    
    /**
     * Gets daily statistics for last 7 days
     */
    suspend fun getDailyStats(days: Int = 7): List<DailyStats> {
        val startDate = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        return connectionDao.getDailyStats(startDate)
    }
    
    /**
     * Cleans up old connections (keep 30 days)
     */
    suspend fun cleanupOldConnections() {
        val cutoff = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
        connectionDao.deleteOldConnections(cutoff)
    }
    
    private fun getTodayStart(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    
    private fun getWeekStart(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}