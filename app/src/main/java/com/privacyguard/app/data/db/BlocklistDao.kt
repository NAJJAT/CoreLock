/**
 * BlocklistDao.kt
 * 
 * Data Access Object for blocklist entries
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.data.db

import androidx.room.*

@Dao
interface BlocklistDao {
    
    /**
     * Inserts a blocklist entry
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BlocklistEntity)
    
    /**
     * Inserts multiple blocklist entries
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<BlocklistEntity>)
    
    /**
     * Gets all blocklist entries
     */
    @Query("SELECT * FROM blocklist ORDER BY domain")
    suspend fun getAllEntries(): List<BlocklistEntity>
    
    /**
     * Gets blocklist entries by source
     */
    @Query("SELECT * FROM blocklist WHERE source = :source ORDER BY domain")
    suspend fun getEntriesBySource(source: String): List<BlocklistEntity>
    
    /**
     * Gets blocklist entries by category
     */
    @Query("SELECT * FROM blocklist WHERE category = :category ORDER BY domain")
    suspend fun getEntriesByCategory(category: String): List<BlocklistEntity>
    
    /**
     * Checks if a domain is in the blocklist
     */
    @Query("SELECT EXISTS(SELECT 1 FROM blocklist WHERE domain = :domain)")
    suspend fun isBlocked(domain: String): Boolean
    
    /**
     * Gets blocklist entry for a domain
     */
    @Query("SELECT * FROM blocklist WHERE domain = :domain")
    suspend fun getEntry(domain: String): BlocklistEntity?
    
    /**
     * Deletes a blocklist entry
     */
    @Query("DELETE FROM blocklist WHERE domain = :domain")
    suspend fun deleteEntry(domain: String)
    
    /**
     * Deletes all entries from a source
     */
    @Query("DELETE FROM blocklist WHERE source = :source")
    suspend fun deleteBySource(source: String)
    
    /**
     * Deletes old entries (older than specified days)
     */
    @Query("DELETE FROM blocklist WHERE lastUpdated < :cutoff")
    suspend fun deleteOldEntries(cutoff: Long)
    
    /**
     * Clears entire blocklist
     */
    @Query("DELETE FROM blocklist")
    suspend fun clearAll()
    
    /**
     * Gets blocklist size
     */
    @Query("SELECT COUNT(*) FROM blocklist")
    suspend fun getSize(): Int
    
    /**
     * Gets statistics by source
     */
    @Query("""
        SELECT source, COUNT(*) as count 
        FROM blocklist 
        GROUP BY source 
        ORDER BY count DESC
    """)
    suspend fun getStatsBySource(): List<SourceStats>
}

data class SourceStats(
    val source: String,
    val count: Int
)