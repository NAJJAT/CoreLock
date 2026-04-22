package com.privacyguard.app.core.blocklist

import android.content.Context
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.remote.BlocklistDownloader
import com.privacyguard.app.data.repository.BlocklistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

object BlocklistManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloader = BlocklistDownloader()
    private val cache = ConcurrentHashMap<String, BlocklistEntry>()

    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size.asStateFlow()

    private val _lastUpdate = MutableStateFlow(0L)
    val lastUpdate: StateFlow<Long> = _lastUpdate.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private var repository: BlocklistRepository? = null

    @Volatile
    private var initialized = false

    private val defaultEntries = listOf(
        BlocklistEntry("doubleclick.net", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING),
        BlocklistEntry("googleadservices.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING),
        BlocklistEntry("googlesyndication.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING),
        BlocklistEntry("google-analytics.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ANALYTICS),
        BlocklistEntry("googletagmanager.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ANALYTICS),
        BlocklistEntry("facebook.net", BlocklistSource.STEVENBLACK, BlocklistCategory.TRACKING),
        BlocklistEntry("scorecardresearch.com", BlocklistSource.STEVENBLACK, BlocklistCategory.TRACKING),
        BlocklistEntry("taboola.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING),
        BlocklistEntry("outbrain.com", BlocklistSource.STEVENBLACK, BlocklistCategory.ADVERTISING)
    )

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            repository = BlocklistRepository(AppDatabase.getInstance(context.applicationContext).blocklistDao())
            cache.clear()
            defaultEntries.forEach { cache[it.normalizedDomain()] = it }
            _size.value = cache.size
            initialized = true
            scope.launch { refreshFromRepository(seedDefaults = true) }
        }
    }

    fun isBlocked(domain: String): Boolean = findMatch(domain) != null

    fun findMatch(domain: String): BlocklistEntry? {
        val normalized = domain.trim().lowercase()
        if (normalized.isBlank()) return null
        return cache.values.firstOrNull { it.isEnabled && it.matches(normalized) }
    }

    fun addCustomDomain(domain: String, category: BlocklistCategory = BlocklistCategory.TRACKING) {
        val normalized = domain.trim().lowercase()
        if (normalized.isBlank()) return
        val entry = BlocklistEntry(normalized, BlocklistSource.CUSTOM, category)
        cache[entry.normalizedDomain()] = entry
        _size.value = cache.size
        scope.launch {
            repository?.addEntry(entry)
            refreshMetadata()
        }
    }

    fun removeCustomDomain(domain: String) {
        val normalized = domain.trim().lowercase()
        cache.remove(normalized)
        _size.value = cache.size
        scope.launch {
            repository?.deleteEntry(normalized)
            refreshMetadata()
        }
    }

    suspend fun updateAllBlocklists(): UpdateResult {
        _isUpdating.value = true
        return try {
            val downloaded = downloader.downloadAll()
            val merged = mutableListOf<BlocklistEntry>()
            downloaded.forEach { (source, content) ->
                if (content != null) {
                    val entries = when (source) {
                        BlocklistSource.STEVENBLACK,
                        BlocklistSource.PETER_LOWE -> BlocklistParser.parseStevenBlack(content, source)
                        BlocklistSource.EASYLIST,
                        BlocklistSource.EASYPRIVACY -> BlocklistParser.parseEasyList(content, source)
                        BlocklistSource.DISCONNECT -> BlocklistParser.parseDisconnect(content, source)
                        BlocklistSource.CUSTOM,
                        BlocklistSource.COMMUNITY -> emptyList()
                    }
                    merged.addAll(entries)
                }
            }

            val customEntries = repository?.getEntriesBySource(BlocklistSource.CUSTOM).orEmpty()
            merged.addAll(customEntries)

            val uniqueEntries = merged.distinctBy { it.normalizedDomain() }
            repository?.updateBlocklist(uniqueEntries)
            refreshFromRepository(seedDefaults = false)
            UpdateResult.Success(
                totalEntries = uniqueEntries.size,
                bySource = uniqueEntries.groupingBy { it.source }.eachCount()
            )
        } catch (e: Exception) {
            UpdateResult.Failure(e.message ?: "Unknown error")
        } finally {
            _isUpdating.value = false
        }
    }

    suspend fun getStats(): BlocklistStats {
        val statsBySource = repository?.getStatsBySource().orEmpty()
        val statsByCategory = repository?.getStatsByCategory().orEmpty()
        return BlocklistStats(
            totalEntries = repository?.getSize() ?: cache.size,
            bySource = statsBySource,
            byCategory = statsByCategory,
            lastUpdate = _lastUpdate.value
        )
    }

    fun getSize(): Int = _size.value

    private suspend fun refreshFromRepository(seedDefaults: Boolean) {
        val repo = repository ?: return
        var entries = repo.getAllEntries()
        if (entries.isEmpty() && seedDefaults) {
            repo.updateBlocklist(defaultEntries)
            entries = repo.getAllEntries()
        }
        cache.clear()
        entries.filter { it.isEnabled }.forEach { cache[it.normalizedDomain()] = it }
        refreshMetadata()
    }

    private fun refreshMetadata() {
        _size.value = cache.size
        _lastUpdate.value = cache.values.maxOfOrNull { it.lastUpdated } ?: 0L
    }
}

data class BlocklistStats(
    val totalEntries: Int,
    val bySource: Map<BlocklistSource, Int>,
    val byCategory: Map<BlocklistCategory, Int>,
    val lastUpdate: Long
)

sealed class UpdateResult {
    data class Success(val totalEntries: Int, val bySource: Map<BlocklistSource, Int>) : UpdateResult()
    data class Failure(val error: String) : UpdateResult()
}
