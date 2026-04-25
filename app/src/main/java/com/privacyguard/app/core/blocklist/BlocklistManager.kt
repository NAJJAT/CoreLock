package com.privacyguard.app.core.blocklist

import android.content.Context
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.remote.BlocklistDownloader
import com.privacyguard.app.data.repository.BlocklistRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object BlocklistManager {

    @Volatile private var context: Context? = null

    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size.asStateFlow()

    private val _lastUpdate = MutableStateFlow(0L)
    val lastUpdate: StateFlow<Long> = _lastUpdate.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val downloader = BlocklistDownloader()

    fun initialize(context: Context) {
        this.context = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val repo = BlocklistRepo(AppDatabase.getInstance(context.applicationContext).blocklistDao())
                _size.value = repo.totalCount()
            }
        }
    }

    suspend fun updateAllBlocklists(): UpdateResult {
        val ctx = context ?: return UpdateResult.Failure("Not initialized")
        if (_isUpdating.value) return UpdateResult.Failure("Update already in progress")
        return try {
            _isUpdating.value = true
            val repo = BlocklistRepo(AppDatabase.getInstance(ctx).blocklistDao())
            var totalEntries = 0
            BlocklistSource.values().forEach { source ->
                if (source.downloadUrl() != null) {
                    val result = updateBlocklistInternal(source, repo)
                    if (result is UpdateResult.Success) totalEntries += result.totalEntries
                }
            }
            _size.value = repo.totalCount()
            _lastUpdate.value = System.currentTimeMillis()
            UpdateResult.Success(totalEntries)
        } catch (e: Exception) {
            UpdateResult.Failure(e.message ?: "Unknown error")
        } finally {
            _isUpdating.value = false
        }
    }

    suspend fun updateBlocklist(source: BlocklistSource): UpdateResult {
        val ctx = context ?: return UpdateResult.Failure("Not initialized")
        return try {
            val repo = BlocklistRepo(AppDatabase.getInstance(ctx).blocklistDao())
            val result = updateBlocklistInternal(source, repo)
            _size.value = repo.totalCount()
            result
        } catch (e: Exception) {
            UpdateResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun updateBlocklistInternal(source: BlocklistSource, repo: BlocklistRepo): UpdateResult {
        val raw = downloader.download(source) ?: return UpdateResult.Failure("Download failed for $source")
        val domains = parseBlocklist(source, raw)
        if (domains.isEmpty()) return UpdateResult.Failure("No valid domains parsed from $source")
        val (sourceName, category) = sourceMetadata(source)
        repo.replaceSource(sourceName, category, domains)
        return UpdateResult.Success(domains.size)
    }

    private fun parseBlocklist(source: BlocklistSource, raw: String): List<String> = when (source) {
        BlocklistSource.STEVENBLACK                             -> parseHostsFormat(raw)
        BlocklistSource.EASYLIST, BlocklistSource.EASYPRIVACY  -> parseEasyListFormat(raw)
        BlocklistSource.OISD_BASIC, BlocklistSource.OISD_FULL,
        BlocklistSource.HAGEZI_LIGHT                           -> parseDomainListFormat(raw)
        BlocklistSource.CUSTOM                                 -> emptyList()
    }

    private fun parseHostsFormat(raw: String): List<String> {
        val result = mutableListOf<String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            val commentStripped = trimmed.substringBefore('#').trim()
            val parts = commentStripped.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val domain = parts[1].lowercase()
            if (domain == "localhost" || domain == "broadcasthost" || domain == "local") continue
            if (isValidDomain(domain)) result += domain
        }
        return result
    }

    private fun parseEasyListFormat(raw: String): List<String> {
        val result = mutableListOf<String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("||") || trimmed.startsWith("@@||")) continue
            val without = trimmed.removePrefix("||")
            val domain = without
                .substringBefore('^')
                .substringBefore('$')
                .substringBefore('/')
                .substringBefore('*')
                .lowercase()
            if (isValidDomain(domain)) result += domain
        }
        return result
    }

    private fun parseDomainListFormat(raw: String): List<String> {
        val result = mutableListOf<String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            val domain = trimmed.substringBefore('#').trim().lowercase()
            if (isValidDomain(domain)) result += domain
        }
        return result
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (domain.startsWith('.') || domain.endsWith('.')) return false
        if (!domain.contains('.')) return false
        if (domain.all { it.isDigit() || it == '.' }) return false  // skip IPv4
        return domain.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }

    private fun sourceMetadata(source: BlocklistSource): Pair<String, String> = when (source) {
        BlocklistSource.STEVENBLACK  -> Pair(BlocklistRepo.SOURCE_STEVEN_BLACK, BlocklistRepo.CAT_ADS)
        BlocklistSource.EASYLIST     -> Pair(BlocklistRepo.SOURCE_EASYLIST, BlocklistRepo.CAT_ADS)
        BlocklistSource.EASYPRIVACY  -> Pair(BlocklistRepo.SOURCE_EASYLIST, BlocklistRepo.CAT_TRACKERS)
        BlocklistSource.OISD_BASIC   -> Pair(BlocklistRepo.SOURCE_OISD, BlocklistRepo.CAT_ADS)
        BlocklistSource.OISD_FULL    -> Pair(BlocklistRepo.SOURCE_OISD, BlocklistRepo.CAT_ADS)
        BlocklistSource.HAGEZI_LIGHT -> Pair("Hagezi", BlocklistRepo.CAT_ADS)
        BlocklistSource.CUSTOM       -> Pair("Custom", BlocklistRepo.CAT_ADS)
    }
}

sealed class UpdateResult {
    data class Success(val totalEntries: Int) : UpdateResult()
    data class Failure(val error: String)     : UpdateResult()
}
