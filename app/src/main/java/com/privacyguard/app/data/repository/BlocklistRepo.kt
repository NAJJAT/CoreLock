package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.BlocklistDao
import com.privacyguard.app.data.db.BlocklistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BlocklistRepo(private val blocklistDao: BlocklistDao) {

    suspend fun replaceSource(source: String, category: String, domains: List<String>) = withContext(Dispatchers.IO) {
        blocklistDao.deleteBySource(source)
        val now = System.currentTimeMillis()
        blocklistDao.insertAll(domains.map { domain ->
            BlocklistEntity(
                domain = domain.lowercase().trim(),
                source = source,
                category = category,
                lastUpdated = now,
            )
        })
    }

    suspend fun allDomains(): List<String> = withContext(Dispatchers.IO) {
        blocklistDao.getAllEntries().map { it.domain }
    }

    suspend fun domainsForCategory(cat: String): List<String> = withContext(Dispatchers.IO) {
        blocklistDao.getEntriesByCategory(cat).map { it.domain }
    }

    suspend fun totalCount(): Int = withContext(Dispatchers.IO) {
        blocklistDao.getSize()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        blocklistDao.clearAll()
    }

    suspend fun deleteBySource(src: String) = withContext(Dispatchers.IO) {
        blocklistDao.deleteBySource(src)
    }

    companion object {
        const val SOURCE_EASYLIST = "EasyList"
        const val SOURCE_STEVEN_BLACK = "StevenBlack"
        const val SOURCE_OISD = "OISD"
        const val CAT_ADS = "ads"
        const val CAT_TRACKERS = "trackers"
        const val CAT_MALWARE = "malware"
        const val CAT_TELEMETRY = "telemetry"
    }
}