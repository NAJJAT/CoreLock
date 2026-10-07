package com.privacyguard.app.data.db

import androidx.room.*
// FIXED: removed wrong import — PayloadLogEntity is in the same package (com.privacyguard.app.data.db)
import kotlinx.coroutines.flow.Flow

@Dao
interface PayloadLogDao {

    @Insert
    suspend fun insert(log: PayloadLogEntity): Long

    @Query("SELECT * FROM payload_logs ORDER BY timestamp DESC LIMIT :limit")
    fun recentLogs(limit: Int = 100): Flow<List<PayloadLogEntity>>

    @Query("SELECT * FROM payload_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentLogsList(limit: Int): List<PayloadLogEntity>

    @Query("SELECT * FROM payload_logs WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    suspend fun logsForSession(sessionId: String): List<PayloadLogEntity>

    @Query("SELECT * FROM payload_logs WHERE ownerPackage = :pkg ORDER BY timestamp DESC LIMIT :limit")
    suspend fun logsForPackage(pkg: String, limit: Int): List<PayloadLogEntity>

    @Query("""
        SELECT * FROM payload_logs 
        WHERE body LIKE '%' || :query || '%' 
           OR urlPath LIKE '%' || :query || '%' 
           OR ownerPackage LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        LIMIT 100
    """)
    suspend fun search(query: String): List<PayloadLogEntity>

    @Query("SELECT COUNT(*) FROM payload_logs WHERE timestamp >= :fromMs")
    suspend fun countSince(fromMs: Long): Long

    @Query("DELETE FROM payload_logs WHERE timestamp < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int

    /**
     * Deletes at most [limit] rows older than [cutoff]. Callers loop until it returns
     * less than [limit], so no single transaction holds the write lock for long.
     */
    @Query("""
        DELETE FROM payload_logs WHERE id IN
            (SELECT id FROM payload_logs WHERE timestamp < :cutoff LIMIT :limit)
    """)
    suspend fun deleteOlderThanBatch(cutoff: Long, limit: Int): Int

    /** Timestamp of the [keep]th newest row, or null if there are fewer than [keep] rows. */
    @Query("SELECT timestamp FROM payload_logs ORDER BY timestamp DESC LIMIT 1 OFFSET :keep - 1")
    suspend fun capCutoff(keep: Int): Long?

    @Query("DELETE FROM payload_logs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM payload_logs")
    suspend fun getTotalCount(): Int
}