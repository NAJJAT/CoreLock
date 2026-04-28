package com.privacyguard.app.core.blocklist

import android.annotation.SuppressLint
import android.content.Context
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.remote.BlocklistDownloader
import com.privacyguard.app.data.repository.BlocklistRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SuppressLint("StaticFieldLeak")
object BlocklistManager {

    @Volatile private var context: Context? = null
    @Volatile private var initStarted = false
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _size = MutableStateFlow(0)
    val size: StateFlow<Int> = _size.asStateFlow()

    private val _lastUpdate = MutableStateFlow(0L)
    val lastUpdate: StateFlow<Long> = _lastUpdate.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val downloader = BlocklistDownloader()

    fun initialize(context: Context) {
        if (initStarted) return
        initStarted = true
        this.context = context.applicationContext
        initScope.launch {
            runCatching {
                val repo = BlocklistRepo(AppDatabase.getInstance(context.applicationContext).blocklistDao())
                if (repo.totalCount() == 0) {
                    seedBuiltInBlocklists(repo)
                }
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
        BlocklistSource.EASYPRIVACY  -> Pair(BlocklistRepo.SOURCE_EASYPRIVACY, BlocklistRepo.CAT_TRACKERS)
        BlocklistSource.OISD_BASIC   -> Pair(BlocklistRepo.SOURCE_OISD, BlocklistRepo.CAT_ADS)
        BlocklistSource.OISD_FULL    -> Pair(BlocklistRepo.SOURCE_OISD, BlocklistRepo.CAT_ADS)
        BlocklistSource.HAGEZI_LIGHT -> Pair(BlocklistRepo.SOURCE_HAGEZI, BlocklistRepo.CAT_ADS)
        BlocklistSource.CUSTOM       -> Pair("Custom", BlocklistRepo.CAT_ADS)
    }

    private suspend fun seedBuiltInBlocklists(repo: BlocklistRepo) {
        BUILTIN_SEED.forEach { (source, categoryDomains) ->
            categoryDomains.forEach { (category, domains) ->
                if (domains.isNotEmpty()) {
                    repo.replaceSource("$source-$category", category, domains.distinct())
                }
            }
        }
    }

    private val BUILTIN_SEED: Map<String, Map<String, List<String>>> = mapOf(
        BlocklistRepo.SOURCE_BUILTIN to mapOf(
            BlocklistRepo.CAT_ADS to listOf(
                "doubleclick.net",
                "ad.doubleclick.net",
                "pagead2.googlesyndication.com",
                "googlesyndication.com",
                "googleadservices.com",
                "adservice.google.com",
                "adservice.google.ae",
                "adservice.google.com.eg",
                "adnxs.com",
                "ads.yahoo.com",
                "taboola.com",
                "outbrain.com",
                "criteo.com",
                "rubiconproject.com",
                "openx.net",
                "pubmatic.com",
                "ads.pubmatic.com",
                "moatads.com",
                "scorecardresearch.com",
                "zedo.com",
                "advertising.com",
                "adform.net",
                "casalemedia.com",
                "quantserve.com",
                "smartadserver.com",
                "amazon-adsystem.com",
                "adsrvr.org",
                "yieldmo.com",
                "adsafeprotected.com",
                "serving-sys.com",
            ),
            BlocklistRepo.CAT_TRACKERS to listOf(
                "analytics.google.com",
                "google-analytics.com",
                "ssl.google-analytics.com",
                "analytics.facebook.com",
                "connect.facebook.net",
                "graph.facebook.com",
                "app-measurement.com",
                "firebaseinstallations.googleapis.com",
                "appsflyer.com",
                "adjust.com",
                "branch.io",
                "segment.io",
                "mixpanel.com",
                "api.mixpanel.com",
                "amplitude.com",
                "telemetry.microsoft.com",
                "settings-win.data.microsoft.com",
                "bat.bing.com",
                "stats.g.doubleclick.net",
                "cdn.segment.com",
                "api.segment.io",
                "pixel.facebook.com",
                "sdk.iad-05.braze.com",
                "braze.com",
                "api2.branch.io",
            ),
            BlocklistRepo.CAT_MALWARE to listOf(
                "malware.testcategory.com",
                "phishing.testcategory.com",
                "urlhaus.abuse.ch",
                "tracker.badexample.ru",
                "c2-tracker.ru",
                "command-and-control.example",
            ),
            BlocklistRepo.CAT_TELEMETRY to listOf(
                "telemetry.dropbox.com",
                "telemetry.mozilla.org",
                "telemetry.android.com",
                "device-metrics-us.amazon.com",
                "settings.data.microsoft.com",
                "browser.events.data.msn.com",
                "safebrowsing.googleapis.com",
                "metrics.icloud.com",
                "metrics.apple.com",
                "diagnostics.support.apple.com",
            ),
        ),
    )
}

sealed class UpdateResult {
    data class Success(val totalEntries: Int) : UpdateResult()
    data class Failure(val error: String)     : UpdateResult()
}
