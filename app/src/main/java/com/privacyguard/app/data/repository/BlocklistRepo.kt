/**
 * BlocklistRepo.kt
 * 
 * Repository for blocklist data
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.data.repository

import com.privacyguard.app.core.filter.BlocklistEntry
import com.privacyguard.app.core.filter.BlocklistCategory
import com.privacyguard.app.core.filter.BlocklistSource
import com.privacyguard.app.data.db.BlocklistDao
import com.privacyguard.app.data.db.BlocklistEntity

class BlocklistRepository(
    private val blocklistDao: BlocklistDao
) {
    
    /**
     * Updates the entire blocklist
     */
    suspend fun updateBlocklist(entries: List<BlocklistEntry>) {
        blocklistDao.clearAll()
        val entities = entries.map { entry ->
            BlocklistEntity(
                domain = entry.domain,
                source = entry.source.name,
                category = entry.category.name,
                lastUpdated = entry.lastUpdated
            )
        }
        blocklistDao.insertAll(entities)
    }
    
    /**
     * Adds a single blocklist entry
     */
    suspend fun addEntry(entry: BlocklistEntry) {
        val entity = BlocklistEntity(
            domain = entry.domain,
            source = entry.source.name,
            category = entry.category.name,
            lastUpdated = entry.lastUpdated
        )
        blocklistDao.insert(entity)
    }
    
    /**
     * Gets all blocklist entries
     */
    suspend fun getAllEntries(): List<BlocklistEntry> {
        return blocklistDao.getAllEntries().map { it.toBlocklistEntry() }
    }
    
    /**
     * Checks if a domain is blocked
     */
    suspend fun isBlocked(domain: String): Boolean {
        return blocklistDao.isBlocked(domain)
    }
    
    /**
     * Gets blocklist entry for a domain
     */
    suspend fun getEntry(domain: String): BlocklistEntry? {
        return blocklistDao.getEntry(domain)?.toBlocklistEntry()
    }
    
    /**
     * Deletes an entry
     */
    suspend fun deleteEntry(domain: String) {
        blocklistDao.deleteEntry(domain)
    }
    
    /**
     * Deletes entries by source
     */
    suspend fun deleteBySource(source: BlocklistSource) {
        blocklistDao.deleteBySource(source.name)
    }
    
    /**
     * Clears entire blocklist
     */
    suspend fun clearAll() {
        blocklistDao.clearAll()
    }
    
    /**
     * Gets blocklist size
     */
    suspend fun getSize(): Int {
        return blocklistDao.getSize()
    }
    
    /**
     * Gets statistics by source
     */
    suspend fun getStatsBySource(): Map<BlocklistSource, Int> {
        val stats = blocklistDao.getStatsBySource()
        return stats.associate { 
            BlocklistSource.valueOf(it.source) to it.count 
        }
    }
    
    /**
     * Loads default blocklist (for first run)
     */
    suspend fun loadDefaultBlocklist() {
        val defaultEntries = listOf(
            // Google trackers
            BlocklistEntry("doubleclick.net", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING),
            BlocklistEntry("googleadservices.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING),
            BlocklistEntry("google-analytics.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ANALYTICS),
            BlocklistEntry("googletagmanager.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ANALYTICS),
            BlocklistEntry("googlesyndication.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING),
            
            // Facebook trackers
            BlocklistEntry("facebook.com/tr", BlocklistSource.STEVENBLACK, BlocklistCategory.ANALYTICS),
            BlocklistEntry("facebook.net", BlocklistSource.STEVENBLACK, BlocklistCategory.ANALYTICS),
            BlocklistEntry("fbcdn.net", BlocklistSource.STEVENBLACK, BlocklistCategory.ANALYTICS),
            
            // Other common trackers
            BlocklistEntry("scorecardresearch.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ANALYTICS),
            BlocklistEntry("outbrain.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING),
            BlocklistEntry("taboola.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING),
            BlocklistEntry("amazon-adsystem.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING)
        )
        updateBlocklist(defaultEntries)
    }
}

/**
 * Converts database entity to BlocklistEntry
 */
fun BlocklistEntity.toBlocklistEntry(): BlocklistEntry {
    return BlocklistEntry(
        domain = domain,
        source = BlocklistSource.valueOf(source),
        category = BlocklistCategory.valueOf(category),
        lastUpdated = lastUpdated
    )
}
