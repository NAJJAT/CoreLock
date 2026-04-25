package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConnectionRepo(private val connectionDao: ConnectionDao) {
    
    suspend fun save(connection: ConnectionEntity) = withContext(Dispatchers.IO) {
        connectionDao.insert(connection)
    }

    suspend fun recent(limit: Int = 200): List<ConnectionEntity> = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        connectionDao.getRecentConnections(since, limit)
    }

    suspend fun forPackage(uid: Int): List<ConnectionEntity> = withContext(Dispatchers.IO) {
        connectionDao.getConnectionsForApp(uid)
    }

    suspend fun recentBlocked(limit: Int = 100): List<ConnectionEntity> = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        connectionDao.getRecentConnections(since, limit).filter { it.wasBlocked }
    }

    suspend fun cleartextConnections(): List<ConnectionEntity> = withContext(Dispatchers.IO) {
        recent(500).filter { !it.wasBlocked }
    }

    suspend fun weakTlsConnections(): List<ConnectionEntity> = withContext(Dispatchers.IO) {
        emptyList()
    }

    suspend fun search(q: String): List<ConnectionEntity> = withContext(Dispatchers.IO) {
        recent(500).filter { it.domain?.contains(q, ignoreCase = true) == true }
    }

    suspend fun blockedCount(): Int = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        connectionDao.getBlockedCountToday(since)
    }

    suspend fun cleartextCount(): Int = withContext(Dispatchers.IO) {
        cleartextConnections().size
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        connectionDao.getTotalDataToday(since) ?: 0L
    }

    suspend fun topBlockedApps(limit: Int = 10): List<AppBlockCount> = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        connectionDao.getTopBlockedDomains(since, limit).map { AppBlockCount(it.domain ?: "", it.count) }
    }

    suspend fun pruneOldRecords(days: Int = 30): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - days.toLong() * 86_400_000
        connectionDao.deleteOldConnections(cutoff)
        0
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        connectionDao.deleteAll()
    }
}

data class AppBlockCount(
    val packageName: String,
    val count: Int,
)