package com.privacyguard.data.repository

import com.privacyguard.app.data.db.PayloadLogDao // FIXED: was com.privacyguard.data.local.database.dao.PayloadLogDao
import com.privacyguard.app.data.db.PayloadLogEntity // FIXED: was com.privacyguard.data.local.database.entity.PayloadLogEntity
import com.privacyguard.domain.repository.PayloadLogRepository
import kotlinx.coroutines.flow.Flow
/**
 * Implementation of PayloadLogRepository
 *
 * Handles database operations for MITM payload logs including insert, query,
 * search, and deletion operations.
 *
 * Business Reason: Provides data persistence layer for intercepted payloads
 * to enable security analysis and compliance reporting.
 *
 * Thread Safety: All Room operations are suspend functions and run on
 * background threads automatically.
 */
/**
 * Implementation of PayloadLogRepository
 *
 * Handles database operations for MITM payload logs including insert, query,
 * search, and deletion operations.
 *
 * Business Reason: Provides data persistence layer for intercepted payloads
 * to enable security analysis and compliance reporting.
 *
 * Thread Safety: All Room operations are suspend functions and run on
 * background threads automatically.
 */
class PayloadLogRepositoryImpl(
    private val dao: PayloadLogDao
) : PayloadLogRepository {

    /**
     * Get recent logs as a Flow (automatically updates when data changes)
     * @param limit Maximum number of logs to return
     * @return Flow of list of payload log entities
     */
    override fun recentLogs(limit: Int): Flow<List<PayloadLogEntity>> =
        dao.recentLogs(limit)

    /**
     * Get all logs for a specific session
     * @param sessionId The session ID to query
     * @return List of payload log entities for that session
     */
    override suspend fun logsForSession(sessionId: String): List<PayloadLogEntity> =
        dao.logsForSession(sessionId)

    /**
     * Get logs for a specific app package
     * @param pkg The package name
     * @param limit Maximum number of logs to return
     * @return List of payload log entities for that package
     */
    override suspend fun logsForPackage(pkg: String, limit: Int): List<PayloadLogEntity> =
        dao.logsForPackage(pkg, limit)

    /**
     * Search logs by body content, URL path, or package name
     * @param query The search query string
     * @return List of matching payload log entities
     */
    override suspend fun search(query: String): List<PayloadLogEntity> =
        dao.search(query)

    /**
     * Save a payload log to database
     * @param log The payload log entity to save
     * @return The ID of the inserted row
     */
    override suspend fun saveLog(log: PayloadLogEntity): Long =
        dao.insert(log)

    /**
     * Delete logs older than specified days
     * @param days Number of days to keep (logs older than this are deleted)
     */
    override suspend fun deleteOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000)
        dao.deleteOlderThan(cutoff)
    }

    /**
     * Get total count of logs in database
     * @return Total number of payload logs
     */
    override suspend fun getTotalCount(): Int =
        dao.getTotalCount()
}