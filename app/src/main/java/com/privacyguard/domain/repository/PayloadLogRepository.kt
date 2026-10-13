// FIXED: Commented stray package line that caused a syntax error.
// package com.privacyguard.app.data.repository

package com.privacyguard.domain.repository

import com.privacyguard.app.data.db.PayloadLogEntity // FIXED: was com.privacyguard.data.local.database.entity.PayloadLogEntity
import kotlinx.coroutines.flow.Flow

interface PayloadLogRepository {
    fun recentLogs(limit: Int): Flow<List<PayloadLogEntity>>
    /** Entries matching every filter, searched in SQL (newest [limit]). */
    fun filtered(query: String, method: String?, direction: String?, hasBody: Boolean, limit: Int): Flow<List<PayloadLogEntity>>
    /** All entries without bodies, for risk totals. */
    fun summaries(): Flow<List<com.privacyguard.app.data.db.PayloadLogSummary>>
    suspend fun logsForSession(sessionId: String): List<PayloadLogEntity>
    suspend fun logsForPackage(pkg: String, limit: Int): List<PayloadLogEntity>
    suspend fun search(query: String): List<PayloadLogEntity>
    suspend fun saveLog(log: PayloadLogEntity): Long
    suspend fun deleteOlderThan(days: Int)
    suspend fun getTotalCount(): Int
}

// ADDED: Compatibility alias for the typo used by existing implementation code.
typealias PayrollLogRepository = PayloadLogRepository
