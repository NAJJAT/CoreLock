package com.privacyguard.app.core.blocklist

import android.annotation.SuppressLint
import android.content.Context
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
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
    // `||host^` with an optional `|` anchor and optional `$options`; no path or wildcard.
    private val EASYLIST_DOMAIN_RULE = Regex("""^\|\|([A-Za-z0-9._-]+)\^\|?(?:\$(.+))?$""")
    // Options that still mean "block this whole domain" at the DNS level.
    private val WHOLE_DOMAIN_OPTIONS = setOf("important", "all", "document", "doc")


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
                val repo = blocklistRepo(context.applicationContext)
                // Re-seeds when the stored built-in rows differ from this version's seed:
                // rows lost before lists stopped overwriting each other (database version 7),
                // or entries removed from the seed since (replaceSource drops them).
                val builtInRows = BUILTIN_SEED.values.sumOf { cats -> cats.values.sumOf { it.distinct().size } }
                if (repo.countBySourcePrefix(BlocklistRepo.SOURCE_BUILTIN) != builtInRows) {
                    seedBuiltInBlocklists(repo)
                    com.privacyguard.app.data.repository.BlocklistSyncBus.publish()
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
            val repo = blocklistRepo(ctx)
            var totalEntries = 0
            BlocklistSource.values().forEach { source ->
                if (source.downloadUrl() != null) {
                    val result = updateBlocklistInternal(source, repo)
                    if (result is UpdateResult.Success) totalEntries += result.totalEntries
                }
            }
            _size.value = repo.totalCount()
            _lastUpdate.value = System.currentTimeMillis()
            // With protection on, the VPN reloads and refreshes the start-up cache itself.
            if (!com.privacyguard.platform.android.PrivacyVpnService.isRunning) runCatching { writeStartupCache(ctx, repo) }
            // The running VPN keeps its own in-memory filter; tell it to reload.
            com.privacyguard.app.data.repository.BlocklistSyncBus.publish()
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
            val repo = blocklistRepo(ctx)
            val result = updateBlocklistInternal(source, repo)
            _size.value = repo.totalCount()
            com.privacyguard.app.data.repository.BlocklistSyncBus.publish()
            result
        } catch (e: Exception) {
            UpdateResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Lists downloaded while protection was off: write the cache the next VPN start
     * reads, so it does not wait half a minute for the encrypted database.
     */
    private suspend fun writeStartupCache(ctx: Context, repo: BlocklistRepo) {
        val levels = com.privacyguard.core.filter.ProtectionLevels
        val level = levels.parse(com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(ctx).protectionLevel.value)
        val blocked = repo.domainsIn(levels.categoriesFor(level)) +
            (if (levels.includesCuratedAdHosts(level)) CuratedAdDomains.all else emptyList())
        BlocklistCache.write(ctx, level.name, blocked, repo.domainsIn(setOf(BlocklistRepo.CAT_MALWARE)))
    }

    private fun blocklistRepo(ctx: Context) = BlocklistRepo(
        AppDatabase.getInstance(ctx).blocklistDao(),
        ConfigDatabase.getInstance(ctx).blocklistToggleDao(),
    )

    private suspend fun updateBlocklistInternal(source: BlocklistSource, repo: BlocklistRepo): UpdateResult {
        // Streamed and validated line by line (V-10); null also when the list is
        // implausibly large, which a tampered source could use to exhaust memory.
        val domains = downloader.downloadLines(source) { lines -> parseLines(source, lines) }
            ?: return UpdateResult.Failure("Download failed for $source")
        if (domains.isEmpty()) return UpdateResult.Failure("No valid domains parsed from $source")
        val (sourceName, category) = sourceMetadata(source)
        repo.replaceSource(sourceName, category, domains.toList())
        return UpdateResult.Success(domains.size)
    }

    /** Distinct valid names of a list, or null past [ListEntryValidator.MAX_ENTRIES_PER_SOURCE]. */
    internal fun parseLines(
        source: BlocklistSource,
        lines: Sequence<String>,
        max: Int = ListEntryValidator.MAX_ENTRIES_PER_SOURCE,
    ): Set<String>? {
        val parseLine: (String) -> String? = when (source) {
            BlocklistSource.STEVENBLACK, BlocklistSource.URLHAUS    -> ::hostsLine
            BlocklistSource.EASYLIST, BlocklistSource.EASYPRIVACY  -> ::easyListLine
            BlocklistSource.OISD_BASIC, BlocklistSource.OISD_FULL,
            BlocklistSource.HAGEZI_LIGHT, BlocklistSource.HAGEZI_DOH -> ::domainListLine
            BlocklistSource.HAGEZI_DOH_IPS                         -> ::ipListLine
            BlocklistSource.CUSTOM                                 -> return emptySet()
        }
        val out = LinkedHashSet<String>()
        for (line in lines) {
            val domain = parseLine(line) ?: continue
            out += domain
            if (out.size > max) return null
        }
        return out
    }

    private fun hostsLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#')) return null
        val parts = trimmed.substringBefore('#').trim().split(WHITESPACE)
        if (parts.size < 2) return null
        val domain = parts[1].lowercase()
        if (domain == "localhost" || domain == "broadcasthost" || domain == "local") return null
        return ListEntryValidator.normalize(domain)
    }

    /**
     * Keeps only EasyList rules that block a whole domain: `||domain^`, optionally
     * with `$important` / `$all`. A DNS blocker can only block whole names, so rules
     * scoped to a path (`||google.com/pagead/`), a wildcard, or a context option
     * (`||github.com^$third-party`) must be skipped. Truncating them to the host
     * blocked google.com, youtube.com, wikipedia.org, github.com and other sites.
     */
    private fun easyListLine(line: String): String? {
        val match = EASYLIST_DOMAIN_RULE.matchEntire(line.trim()) ?: return null
        val options = match.groupValues[2].takeIf { it.isNotEmpty() }?.split(',').orEmpty()
        if (options.any { it !in WHOLE_DOMAIN_OPTIONS }) return null
        return ListEntryValidator.normalize(match.groupValues[1])
    }

    private fun domainListLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#')) return null
        return ListEntryValidator.normalize(trimmed.substringBefore('#').trim())
    }

    private fun ipListLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#')) return null
        return ListEntryValidator.normalizeIp(trimmed.substringBefore('#').trim())
    }

    // Whole-text forms of the line parsers (tests).
    internal fun parseEasyListFormat(raw: String): List<String> = raw.lineSequence().mapNotNull(::easyListLine).toList()
    private fun parseHostsFormat(raw: String): List<String> = raw.lineSequence().mapNotNull(::hostsLine).toList()
    private fun parseDomainListFormat(raw: String): List<String> = raw.lineSequence().mapNotNull(::domainListLine).toList()

    private val WHITESPACE = Regex("\\s+")

    private fun sourceMetadata(source: BlocklistSource): Pair<String, String> = when (source) {
        BlocklistSource.STEVENBLACK  -> Pair(BlocklistRepo.SOURCE_STEVEN_BLACK, BlocklistRepo.CAT_ADS)
        BlocklistSource.EASYLIST     -> Pair(BlocklistRepo.SOURCE_EASYLIST, BlocklistRepo.CAT_ADS)
        BlocklistSource.EASYPRIVACY  -> Pair(BlocklistRepo.SOURCE_EASYPRIVACY, BlocklistRepo.CAT_TRACKERS)
        BlocklistSource.OISD_BASIC   -> Pair(BlocklistRepo.SOURCE_OISD, BlocklistRepo.CAT_ADS)
        BlocklistSource.OISD_FULL    -> Pair(BlocklistRepo.SOURCE_OISD, BlocklistRepo.CAT_ADS)
        BlocklistSource.HAGEZI_LIGHT -> Pair(BlocklistRepo.SOURCE_HAGEZI, BlocklistRepo.CAT_ADS)
        BlocklistSource.URLHAUS      -> Pair(BlocklistRepo.SOURCE_URLHAUS, BlocklistRepo.CAT_MALWARE)
        BlocklistSource.HAGEZI_DOH   -> Pair(BlocklistRepo.SOURCE_HAGEZI_DOH, BlocklistRepo.CAT_DOH)
        BlocklistSource.HAGEZI_DOH_IPS -> Pair(BlocklistRepo.SOURCE_HAGEZI_DOH_IPS, BlocklistRepo.CAT_DOH_IP)
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
            ) + CuratedAdDomains.snapchatAds,
            // Login (graph.facebook.com), push (firebaseinstallations) and deep-link
            // (branch.io) hosts are deliberately absent: see AdSdkCatalog.neverBlock.
            BlocklistRepo.CAT_TRACKERS to listOf(
                "analytics.google.com",
                "google-analytics.com",
                "ssl.google-analytics.com",
                "analytics.facebook.com",
                "connect.facebook.net",
                "app-measurement.com",
                "appsflyer.com",
                "adjust.com",
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
            ) + CuratedAdDomains.snapchatTrackers,
            BlocklistRepo.CAT_MALWARE to listOf(
                "malware.testcategory.com",
                "phishing.testcategory.com",
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
