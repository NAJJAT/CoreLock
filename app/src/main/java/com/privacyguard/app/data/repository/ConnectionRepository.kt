package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.ConnectionDao
import com.privacyguard.app.data.db.DailyStats
import com.privacyguard.app.data.db.HourlyStats
import com.privacyguard.app.data.db.TopBlockedDomain

class ConnectionRepository(private val connectionDao: ConnectionDao) {
    private fun sinceForDays(days: Int): Long = System.currentTimeMillis() - days.toLong() * 86_400_000L

    suspend fun getBlockedCountToday(): Int {
        return getBlockedCount(days = 1)
    }

    suspend fun getBlockedCount(days: Int): Int {
        return connectionDao.getBlockedCountToday(sinceForDays(days))
    }

    suspend fun getTotalDataToday(): Long {
        return getTotalData(days = 1)
    }

    suspend fun getTotalData(days: Int): Long {
        return connectionDao.getTotalDataToday(sinceForDays(days))
    }

    suspend fun getTopBlockedDomains(limit: Int = 10): List<TopBlockedDomain> {
        return getTopBlockedDomains(days = 1, limit = limit)
    }

    suspend fun getTopBlockedDomains(days: Int, limit: Int = 10): List<TopBlockedDomain> {
        return connectionDao.getTopBlockedDomains(sinceForDays(days), limit)
    }

    suspend fun getHourlyStats(): List<HourlyStats> {
        return getHourlyStats(days = 1)
    }

    suspend fun getHourlyStats(days: Int): List<HourlyStats> {
        return connectionDao.getHourlyStats(sinceForDays(days))
    }

    suspend fun getDailyStats(days: Int = 7): List<DailyStats> {
        return connectionDao.getDailyStats(sinceForDays(days))
    }
}
