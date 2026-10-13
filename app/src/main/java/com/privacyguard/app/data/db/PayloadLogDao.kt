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

    /**
     * The list behind the Payload screen: every filter and the search run here, in
     * SQL, over all stored entries (newest [limit]), not over a preloaded page.
     */
    @Query("""
        SELECT * FROM payload_logs
        WHERE (ownerPackage IS NULL OR ownerPackage NOT LIKE 'com.privacyguard.app%')
          AND (:direction IS NULL OR direction = :direction)
          AND (:method IS NULL OR UPPER(method) = :method)
          AND (:hasBody = 0 OR (body IS NOT NULL AND body != ''))
          AND (:query = ''
               OR sniHostname LIKE '%' || :query || '%'
               OR ownerPackage LIKE '%' || :query || '%'
               OR urlPath LIKE '%' || :query || '%'
               OR body LIKE '%' || :query || '%')
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun filtered(query: String, method: String?, direction: String?, hasBody: Boolean, limit: Int): Flow<List<PayloadLogEntity>>

    /** Everything the risk rules and analytics need, without bodies (which can be 32 KB each). */
    @Query("""
        SELECT id, ownerPackage, method, direction, protocol, headers, piiRedacted, sizeBytes,
               LENGTH(body) AS bodyLength, destinationPort, isMitmSuccess, bodyEncoding
        FROM payload_logs
        WHERE ownerPackage IS NULL OR ownerPackage NOT LIKE 'com.privacyguard.app%'
    """)
    fun summariesFlow(): Flow<List<PayloadLogSummary>>

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
/** A payload entry without its body: what risk scoring and analytics read. */
data class PayloadLogSummary(
    val id: Long,
    val ownerPackage: String?,
    val method: String?,
    val direction: String,
    val protocol: String,
    val headers: String,
    val piiRedacted: Boolean,
    val sizeBytes: Int,
    val bodyLength: Int?,
    val destinationPort: Int,
    val isMitmSuccess: Boolean,
    val bodyEncoding: String,
)
