package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.BlocklistDao
import com.privacyguard.app.data.db.BlocklistEntity
import com.privacyguard.app.data.db.BlocklistToggleDao
import com.privacyguard.app.data.db.BlocklistToggleEntity
import com.privacyguard.app.data.db.BlocklistToggleEntity.Companion.SCOPE_CATEGORY
import com.privacyguard.app.data.db.BlocklistToggleEntity.Companion.SCOPE_SOURCE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloaded domains live in history (a re-downloadable cache); which sources and
 * categories the user switched off lives in config.db and is re-applied to the
 * cache whenever either changes.
 */
class BlocklistRepo(
    private val blocklistDao: BlocklistDao,
    private val toggleDao: BlocklistToggleDao,
) {

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
        applyToggles()
    }

    suspend fun setSourceEnabled(source: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        toggleDao.upsert(BlocklistToggleEntity(SCOPE_SOURCE, source, enabled))
        applyToggles()
    }

    suspend fun setCategoryEnabled(category: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        toggleDao.upsert(BlocklistToggleEntity(SCOPE_CATEGORY, category, enabled))
        applyToggles()
    }

    suspend fun setAllEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        if (enabled) {
            toggleDao.deleteAll()
        } else {
            toggleDao.upsertAll(
                blocklistDao.sources().map { BlocklistToggleEntity(SCOPE_SOURCE, it, false) } +
                    blocklistDao.categories().map { BlocklistToggleEntity(SCOPE_CATEGORY, it, false) }
            )
        }
        applyToggles()
    }

    private suspend fun applyToggles() {
        blocklistDao.applyToggles(toggleDao.disabled(SCOPE_SOURCE), toggleDao.disabled(SCOPE_CATEGORY))
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
        const val SOURCE_EASYPRIVACY = "EasyPrivacy"
        const val SOURCE_STEVEN_BLACK = "StevenBlack"
        const val SOURCE_OISD = "OISD"
        const val SOURCE_HAGEZI = "Hagezi"
        const val SOURCE_BUILTIN = "BuiltIn"
        const val CAT_ADS = "ads"
        const val CAT_TRACKERS = "trackers"
        const val CAT_MALWARE = "malware"
        const val CAT_TELEMETRY = "telemetry"
    }
}
