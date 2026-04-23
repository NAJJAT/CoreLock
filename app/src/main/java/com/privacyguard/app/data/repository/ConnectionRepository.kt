package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.ConnectionDao
import com.privacyguard.app.data.db.DailyStats
import com.privacyguard.app.data.db.HourlyStats
import com.privacyguard.app.data.db.TopBlockedDomain

class ConnectionRepository(private val connectionDao: ConnectionDao) {

    suspend fun getBlockedCountToday(): Int {
        val since = System.currentTimeMillis() - 86_400_000L
        return connectionDao.getBlockedCountToday(since)
    }

    suspend fun getTotalDataToday(): Long {
        val since = System.currentTimeMillis() - 86_400_000L
        return connectionDao.getTotalDataToday(since)
    }

    suspend fun getTopBlockedDomains(limit: Int = 10): List<TopBlockedDomain> {
        val since = System.currentTimeMillis() - 86_400_000L
        return connectionDao.getTopBlockedDomains(since, limit)
    }

    suspend fun getHourlyStats(): List<HourlyStats> {
        val since = System.currentTimeMillis() - 86_400_000L
        return connectionDao.getHourlyStats(since)
    }

    suspend fun getDailyStats(days: Int = 7): List<DailyStats> {
        val since = System.currentTimeMillis() - days.toLong() * 86_400_000L
        return connectionDao.getDailyStats(since)
    }
}
