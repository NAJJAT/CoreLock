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
import kotlinx.coroutines.withContext

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
                // After an update that changed the sources, fetch the new lists now rather
                // than at the next weekly run (the old ones stay until they arrive).
                if (repo.countBySourcePrefix(BlocklistRepo.SOURCE_HAGEZI_TIF_MINI) == 0) {
                    com.privacyguard.app.workers.BlocklistUpdateWorker.updateNow(context.applicationContext)
                }
            }
        }
    }

    /**
     * Downloads every list whose last success is older than [dueBefore] (all of them by
     * default). Failure when any list failed, so the worker retries; the lists that
     * succeeded are stored and are not fetched again by that retry.
     */
    suspend fun updateAllBlocklists(dueBefore: Long = Long.MAX_VALUE): UpdateResult = withContext(Dispatchers.IO) {
        // IO for every caller: the Ads screen calls this from viewModelScope (main), and
        // the update times are written with a blocking commit().
        val ctx = context ?: return@withContext UpdateResult.Failure("Not initialized")
        // Atomic: the one-time and the two periodic jobs can start together. Inside the
        // IO block, so a job cancelled before it starts cannot leave the flag set.
        if (!_isUpdating.compareAndSet(false, true)) return@withContext UpdateResult.Failure("Update already in progress")
        try {
            val repo = blocklistRepo(ctx)
            val times = PrefsBlocklistUpdateTimes(ctx)
            val rejections = PrefsBlocklistRejections(ctx)
            val outcome = refreshDue(times, dueBefore) { updateBlocklistInternal(it, repo, rejections) }
            if (outcome.updated.isNotEmpty()) {
                retireReplacedSources(repo, succeededAtLeastOnce(times))
                _size.value = repo.totalCount()
                _lastUpdate.value = System.currentTimeMillis()
                // With protection on, the VPN reloads and refreshes the start-up cache itself.
                if (!com.privacyguard.platform.android.PrivacyVpnService.isRunning) runCatching { writeStartupCache(ctx, repo) }
                // The running VPN keeps its own in-memory filter; tell it to reload.
                com.privacyguard.app.data.repository.BlocklistSyncBus.publish()
            }
            outcome.toResult()
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
            val result = updateBlocklistInternal(source, repo, PrefsBlocklistRejections(ctx))
            _size.value = repo.totalCount()
            com.privacyguard.app.data.repository.BlocklistSyncBus.publish()
            result
        } catch (e: Exception) {
            UpdateResult.Failure(e.message ?: "Unknown error")
        }
    }

    internal data class RefreshOutcome(
        val updated: Set<BlocklistSource>,
        val failed: Set<BlocklistSource>,
        val totalEntries: Int,
    ) {
        fun toResult(): UpdateResult =
            if (failed.isEmpty()) UpdateResult.Success(totalEntries)
            else UpdateResult.Failure("Download failed for ${failed.joinToString()}")
    }

    /**
     * Runs [update] for every downloadable list last successful before [dueBefore] and
     * records the time of each success. A failed list keeps its old rows and its old
     * time, so it is due again on the next run. A time after now is due too: the clock
     * was ahead when it was stored, and the list would otherwise wait until the clock
     * caught up.
     */
    internal suspend fun refreshDue(
        times: BlocklistUpdateTimes,
        dueBefore: Long,
        now: () -> Long = System::currentTimeMillis,
        update: suspend (BlocklistSource) -> UpdateResult,
    ): RefreshOutcome {
        val updated = mutableSetOf<BlocklistSource>()
        val failed = mutableSetOf<BlocklistSource>()
        var totalEntries = 0
        val startedAt = now()
        for (source in BlocklistSource.values()) {
            if (source.downloadUrl() == null) continue
            val last = times.lastSuccess(source)
            if (last >= dueBefore && last <= startedAt) continue
            when (val result = update(source)) {
                is UpdateResult.Success -> {
                    times.recordSuccess(source, now())
                    totalEntries += result.totalEntries
                    updated += source
                }
                is UpdateResult.Failure -> failed += source
            }
        }
        return RefreshOutcome(updated, failed, totalEntries)
    }

    /**
     * Rows of lists this version no longer downloads (October 2026: StevenBlack,
     * EasyList, EasyPrivacy and HaGeZi Light replaced by HaGeZi Pro; URLhaus, gone,
     * by HaGeZi TIF) are deleted only once their replacement has downloaded, so an
     * update never leaves a level with fewer names in between. [updated]: the lists
     * that have succeeded at least once, in this run or an earlier one.
     */
    private suspend fun retireReplacedSources(repo: BlocklistRepo, updated: Set<BlocklistSource>) {
        val retired = retiredSourceNames(updated)
        if (retired.isNotEmpty()) repo.removeSources(retired)
    }

    /** Lists with a stored success time; none on an install updated from 1.0.0 builds before this. */
    internal fun succeededAtLeastOnce(times: BlocklistUpdateTimes): Set<BlocklistSource> =
        BlocklistSource.values().filter { times.lastSuccess(it) > 0 }.toSet()

    /** Only names of lists no longer downloaded; never a current one (RetireSourcesTest). */
    internal fun retiredSourceNames(updated: Set<BlocklistSource>): List<String> = buildList {
        if (BlocklistSource.HAGEZI_PRO_MINI in updated && BlocklistSource.HAGEZI_PRO in updated) addAll(BlocklistRepo.RETIRED_AD_SOURCES)
        if (BlocklistSource.HAGEZI_TIF_MINI in updated) addAll(BlocklistRepo.RETIRED_SECURITY_SOURCES)
    }

    /**
     * Lists downloaded while protection was off: write the cache the next VPN start
     * reads, so it does not wait half a minute for the encrypted database.
     */
    private suspend fun writeStartupCache(ctx: Context, repo: BlocklistRepo) {
        val levels = com.privacyguard.core.filter.ProtectionLevels
        val level = levels.parse(com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(ctx).protectionLevel.value)
        val blocked = repo.levelListDomains(level) +
            (if (levels.includesCuratedAdHosts(level)) CuratedAdDomains.all else emptyList())
        BlocklistCache.write(ctx, level.name, blocked, repo.domainsIn(setOf(BlocklistRepo.CAT_MALWARE)))
    }

    private fun blocklistRepo(ctx: Context) = BlocklistRepo(
        AppDatabase.getInstance(ctx).blocklistDao(),
        ConfigDatabase.getInstance(ctx).blocklistToggleDao(),
    )

    private suspend fun updateBlocklistInternal(
        source: BlocklistSource,
        repo: BlocklistRepo,
        rejections: BlocklistRejections,
    ): UpdateResult {
        // Streamed and validated line by line (V-10); null also when the list is
        // implausibly large, which a tampered source could use to exhaust memory.
        val domains = downloader.downloadLines(source) { lines -> parseLines(source, lines) }
            ?: return UpdateResult.Failure("Download failed for $source")
        return storeDownloaded(source, domains, repo, rejections)
    }

    /**
     * Replaces the stored list with [domains], unless it has fewer than half of the
     * stored names (ADR 0011): a broken upstream build or a truncated mirror must not
     * empty protection. Then the old list stays, the event is kept for Diagnostics,
     * and the result is a failure so the worker retries. A first download (nothing
     * stored) is never refused.
     */
    internal suspend fun storeDownloaded(
        source: BlocklistSource,
        domains: Set<String>,
        repo: BlocklistRepo,
        rejections: BlocklistRejections,
        now: Long = System.currentTimeMillis(),
    ): UpdateResult {
        if (domains.isEmpty()) return UpdateResult.Failure("No valid domains parsed from $source")
        val (sourceName, category) = sourceMetadata(source)
        val stored = repo.countBySource(sourceName)
        if (shrinksTooMuch(stored, domains.size)) {
            rejections.record(source, stored, domains.size, now)
            return UpdateResult.Failure("$source rejected: ${domains.size} names, $stored stored")
        }
        repo.replaceSource(sourceName, category, domains.toList())
        return UpdateResult.Success(domains.size)
    }

    /** Under half of what is stored; Long, so no count can overflow. */
    internal fun shrinksTooMuch(stored: Int, downloaded: Int): Boolean =
        stored > 0 && downloaded.toLong() * 2 < stored

    /** Distinct valid names of a list, or null past [ListEntryValidator.MAX_ENTRIES_PER_SOURCE]. */
    internal fun parseLines(
        source: BlocklistSource,
        lines: Sequence<String>,
        max: Int = ListEntryValidator.MAX_ENTRIES_PER_SOURCE,
    ): Set<String>? {
        val parseLine: (String) -> String? = when (source) {
            BlocklistSource.OISD_BASIC, BlocklistSource.OISD_FULL,
            BlocklistSource.HAGEZI_PRO_MINI, BlocklistSource.HAGEZI_PRO,
            BlocklistSource.HAGEZI_TIF_MINI, BlocklistSource.HAGEZI_DOH -> ::domainListLine
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

    // Whole-text form of the line parser (tests).
    private fun parseDomainListFormat(raw: String): List<String> = raw.lineSequence().mapNotNull(::domainListLine).toList()

    internal fun sourceMetadata(source: BlocklistSource): Pair<String, String> = when (source) {
        BlocklistSource.OISD_BASIC   -> Pair(BlocklistRepo.SOURCE_OISD, BlocklistRepo.CAT_ADS)
        BlocklistSource.OISD_FULL    -> Pair(BlocklistRepo.SOURCE_OISD, BlocklistRepo.CAT_ADS)
        BlocklistSource.HAGEZI_PRO_MINI -> Pair(BlocklistRepo.SOURCE_HAGEZI_PRO_MINI, BlocklistRepo.CAT_ADS)
        BlocklistSource.HAGEZI_PRO   -> Pair(BlocklistRepo.SOURCE_HAGEZI_PRO, BlocklistRepo.CAT_ADS)
        BlocklistSource.HAGEZI_TIF_MINI -> Pair(BlocklistRepo.SOURCE_HAGEZI_TIF_MINI, BlocklistRepo.CAT_MALWARE)
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

    internal val BUILTIN_SEED: Map<String, Map<String, List<String>>> = mapOf(
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
            // No safebrowsing.googleapis.com: blocking it turns off Chrome's phishing and
            // malware warnings (BuiltInSeedTest).
            BlocklistRepo.CAT_TELEMETRY to listOf(
                "telemetry.dropbox.com",
                "telemetry.mozilla.org",
                "telemetry.android.com",
                "device-metrics-us.amazon.com",
                "settings.data.microsoft.com",
                "browser.events.data.msn.com",
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
