package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.BlocklistDao
import com.privacyguard.app.data.db.BlocklistEntity
import com.privacyguard.app.data.db.BlocklistToggleDao
import com.privacyguard.app.data.db.BlocklistToggleEntity
import com.privacyguard.app.data.db.BlocklistToggleEntity.Companion.SCOPE_CATEGORY
import com.privacyguard.app.data.db.BlocklistToggleEntity.Companion.SCOPE_SOURCE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
        val now = System.currentTimeMillis()
        val entries = domains.map { domain ->
            BlocklistEntity(
                domain = domain.lowercase().trim(),
                source = source,
                category = category,
                lastUpdated = now,
            )
        }
        // The switches live in config.db, so they are read before the history transaction.
        blocklistDao.replaceSource(source, entries, toggleDao.disabled(SCOPE_SOURCE), toggleDao.disabled(SCOPE_CATEGORY))
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
        blocklistDao.enabledDomains()
    }

    suspend fun domainsIn(categories: Set<String>): List<String> = withContext(Dispatchers.IO) {
        blocklistDao.enabledDomainsIn(categories.toList())
    }

    /**
     * The database part of the DNS filter's level list at [level]: the level's categories
     * and its own ad lists. Malware names are left out: the security filter holds them
     * already and the decision checks it first (a second copy of HaGeZi TIF cost about
     * 20 MB of heap).
     */
    suspend fun levelListDomains(level: com.privacyguard.core.filter.FilterEngine.BlockLevel): List<String> = withContext(Dispatchers.IO) {
        val levels = com.privacyguard.core.filter.ProtectionLevels
        val categories = (levels.categoriesFor(level) - CAT_MALWARE).toList()
        val excluded = levels.excludedSourcesFor(level).toList()
        paged { after, limit -> blocklistDao.enabledDomainsForLevelPage(categories, excluded, after, limit) }
    }

    suspend fun domainsNotIn(categories: Set<String>): List<String> = withContext(Dispatchers.IO) {
        val excluded = categories.toList()
        paged { after, limit -> blocklistDao.enabledDomainsNotInPage(excluded, after, limit) }
    }

    /**
     * True when a malware or phishing list has this domain or a parent of it (also as
     * "*.parent"). Allowing such a domain needs the user to confirm a warning.
     */
    suspend fun isSecurityThreat(domain: String): Boolean = withContext(Dispatchers.IO) {
        blocklistDao.anyInCategory(CAT_MALWARE, securityCandidates(domain))
    }

    suspend fun domainsForCategory(cat: String): List<String> = withContext(Dispatchers.IO) {
        blocklistDao.getEntriesByCategory(cat).map { it.domain }
    }

    suspend fun countBySourcePrefix(prefix: String): Int = withContext(Dispatchers.IO) {
        blocklistDao.countBySourcePrefix(prefix)
    }

    suspend fun countBySource(source: String): Int = withContext(Dispatchers.IO) {
        blocklistDao.countBySource(source)
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

    /** Deletes every row of [sources] that is still stored. */
    suspend fun removeSources(sources: Collection<String>) = withContext(Dispatchers.IO) {
        val stored = blocklistDao.sources().toSet()
        sources.filter { it in stored }.forEach { blocklistDao.deleteBySource(it) }
    }

    companion object {
        /** Rows per page of a paged read; see [paged]. */
        internal const val PAGE_ROWS = 20_000

        /**
         * Reads a list page by page, in id order, until a page comes back short.
         *
         * Why: the level's list and the tracker list are most of the table, and one
         * running query cannot be cancelled. On the emulator such a read took minutes;
         * a stopped VPN service waited for it and stayed in memory, lists included, so
         * quick start/stop cycles piled up several services and ended in
         * OutOfMemoryError. Between pages the read stops when its coroutine is cancelled.
         *
         * A list refresh while reading (BlocklistDao.replaceSource) deletes old rows and
         * adds new ones with higher ids, so the result is the new list, or old and new
         * together for that one source: never a list with names missing.
         */
        internal suspend fun paged(page: suspend (afterId: Long, limit: Int) -> List<com.privacyguard.app.data.db.DomainRow>): List<String> {
            val out = ArrayList<String>()
            var after = Long.MIN_VALUE
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val rows = page(after, PAGE_ROWS)
                rows.mapTo(out) { it.domain }
                if (rows.size < PAGE_ROWS) return out
                after = rows.last().id
            }
        }

        /** The domain, each parent, and each as a "*." wildcard entry. */
        fun securityCandidates(domain: String): List<String> {
            val labels = domain.trim().lowercase().trimEnd('.').removePrefix("*.").split('.').filter { it.isNotEmpty() }
            return labels.indices.filter { labels.size - it >= 2 }
                .map { labels.drop(it).joinToString(".") }
                .flatMap { listOf(it, "*.$it") }
        }

        const val SOURCE_OISD = "OISD"
        const val SOURCE_HAGEZI_PRO_MINI = "Hagezi-Pro-mini"
        const val SOURCE_HAGEZI_PRO = "Hagezi-Pro"
        const val SOURCE_HAGEZI_TIF_MINI = "Hagezi-TIF-mini"
        const val SOURCE_BUILTIN = "BuiltIn"
        /** Ad and tracker lists earlier versions downloaded (now covered by HaGeZi Pro). */
        val RETIRED_AD_SOURCES = listOf("StevenBlack", "EasyList", "EasyPrivacy", "Hagezi")
        /** URLhaus's host file, gone since October 2026 (replaced by HaGeZi TIF). */
        val RETIRED_SECURITY_SOURCES = listOf("URLhaus")
        const val CAT_ADS = "ads"
        const val CAT_TRACKERS = "trackers"
        const val CAT_MALWARE = "malware"
        const val CAT_TELEMETRY = "telemetry"
        const val SOURCE_HAGEZI_DOH = "Hagezi-DoH"
        const val SOURCE_HAGEZI_DOH_IPS = "Hagezi-DoH-IPs"
        /** Encrypted DNS endpoints: read by DnsBypassGuard, never part of DNS blocking. */
        const val CAT_DOH = "doh"
        /** Addresses of encrypted DNS endpoints, stored in the domain column. */
        const val CAT_DOH_IP = "doh_ip"
        /** Not tracker lists: kept out of risk scoring's known trackers. */
        val BYPASS_CATEGORIES = setOf(CAT_DOH, CAT_DOH_IP)
    }
}
