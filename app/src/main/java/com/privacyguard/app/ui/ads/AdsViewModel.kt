package com.privacyguard.app.ui.ads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.tracker.TrackerDatabase
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.db.CategoryToggleStats
import com.privacyguard.app.data.db.SourceToggleStats
import com.privacyguard.app.data.repository.BlocklistRepo
import com.privacyguard.app.data.repository.BlocklistSyncBus
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdCategoryUi(
    val id: String,
    val title: String,
    val subtitle: String,
    val totalCount: Int,
    val enabledCount: Int,
    val isEnabled: Boolean,
    /** False: the current protection level does not use it (it shows "Not used"). */
    val usedAtLevel: Boolean = true,
)

data class BlocklistSourceUi(
    val source: String,
    val totalCount: Int,
    val enabledCount: Int,
    val isEnabled: Boolean,
    val usedAtLevel: Boolean = true,
)

data class CustomDomainRuleUi(
    val id: String,
    val domain: String,
    val isAllow: Boolean,
)

data class TrackerAppUi(val appName: String, val packageName: String, val trackerHits: Int)

data class AdsState(
    val masterEnabled: Boolean = true,
    val blockedToday: Int = 0,
    val blockRate: Int = 0,
    val categories: List<AdCategoryUi> = emptyList(),
    val sources: List<BlocklistSourceUi> = emptyList(),
    val customRules: List<CustomDomainRuleUi> = emptyList(),
    val totalDomainsLoaded: Int = 0,
    /** Protection level whose lists [totalDomainsLoaded] counts (MINIMAL, STANDARD, STRICT). */
    val level: String = "STANDARD",
    val isRefreshing: Boolean = false,
    val topTrackerApps: List<TrackerAppUi> = emptyList(),
)

class AdsViewModel(app: Application) : AndroidViewModel(app) {
    private fun str(id: Int): String = getApplication<android.app.Application>().getString(id)
    private val db = AppDatabase.getInstance(app)
    private val configDb = ConfigDatabase.getInstance(app)
    private val rulesRepo = RulesRepo(configDb.rulesDao(), FilterEngine())
    private val blocklistDao = db.blocklistDao()
    private val blocklistRepo = BlocklistRepo(blocklistDao, configDb.blocklistToggleDao())
    private val connectionDao = db.connectionDao()

    private val _state = MutableStateFlow(AdsState())
    val state: StateFlow<AdsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sourceStats = runCatching { blocklistDao.getSourceToggleStats() }.getOrDefault(emptyList())
            // What the current level really blocks (distinct names), not every list on disk.
            val level = com.privacyguard.core.filter.ProtectionLevels.parse(
                com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(getApplication()).protectionLevel.value)
            val domainsAtLevel = runCatching {
                blocklistDao.enabledCountForLevel(com.privacyguard.core.filter.ProtectionLevels.categoriesFor(level).toList(), com.privacyguard.core.filter.ProtectionLevels.excludedSourcesFor(level).toList()) +
                    (if (com.privacyguard.core.filter.ProtectionLevels.includesCuratedAdHosts(level)) com.privacyguard.app.core.blocklist.CuratedAdDomains.all.size else 0)
            }.getOrDefault(0)
            val categoryStats = runCatching { blocklistDao.getCategoryToggleStats() }.getOrDefault(emptyList())
            val userRules = runCatching { rulesRepo.userRules() }.getOrDefault(emptyList())
            val since = System.currentTimeMillis() - 86_400_000L
            // Same count and window as Home, Activity, Statistics and the notification:
            // since midnight (BlockStats.today); the rate uses the same window.
            val nowMs = System.currentTimeMillis()
            val blocks = runCatching {
                com.privacyguard.app.data.repository.BlockStats(db).between(com.privacyguard.app.data.repository.BlockStats.startOfDay(nowMs), nowMs)
            }.getOrNull()
            val blockedToday = runCatching { com.privacyguard.app.data.repository.BlockStats(db).today(nowMs) }.getOrDefault(0)

            val recentConns = runCatching {
                connectionDao.between(since, System.currentTimeMillis())
            }.getOrDefault(emptyList())
            val topTrackerApps = recentConns
                .filter { conn ->
                    val host = conn.sniHostname ?: conn.domain ?: return@filter false
                    // Same rule as the traffic screens: a known tracker, unless it is the app maker's own.
                    val cls = com.privacyguard.app.core.tracker.DomainClassifier.classify(host, conn.packageName.ifBlank { null })
                    cls.trackerName != null && cls.purpose != com.privacyguard.app.core.tracker.DomainPurpose.FIRST_PARTY
                }
                .groupBy { it.packageName }
                .filter { it.key.isNotBlank() }
                .map { (pkg, conns) ->
                    TrackerAppUi(
                        appName = com.privacyguard.app.ui.traffic.AppNames.label(getApplication(), pkg, conns.firstOrNull { it.appName.isNotBlank() }?.appName),
                        packageName = pkg,
                        trackerHits = conns.size,
                    )
                }
                .sortedByDescending { it.trackerHits }
                .take(5)

            _state.value = AdsState(
                masterEnabled = sourceStats.any { it.enabledCount > 0 } || categoryStats.any { it.enabledCount > 0 },
                blockedToday = blockedToday,
                blockRate = ((blocks?.lookupBlockRate ?: 0f) * 100f).toInt(),
                categories = categoryStats.toCategoryUi(level),
                sources = sourceStats.toSourceUi(level, runCatching { blocklistDao.sourceCategories() }.getOrDefault(emptyList()).groupBy({ it.source }, { it.category })),
                customRules = userRules
                    .filter { !it.matchDomain.isNullOrBlank() }
                    .map { CustomDomainRuleUi(it.id, it.matchDomain.orEmpty(), it.action == FilterRule.Action.ALLOW) },
                totalDomainsLoaded = domainsAtLevel,
                level = level.name,
                isRefreshing = false,
                topTrackerApps = topTrackerApps,
            )
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            blocklistRepo.setAllEnabled(enabled)
            BlocklistSyncBus.publish()
            refresh()
        }
    }

    fun setCategoryEnabled(category: String, enabled: Boolean) {
        viewModelScope.launch {
            blocklistRepo.setCategoryEnabled(category, enabled)
            BlocklistSyncBus.publish()
            refresh()
        }
    }

    fun setSourceEnabled(source: String, enabled: Boolean) {
        viewModelScope.launch {
            blocklistRepo.setSourceEnabled(source, enabled)
            BlocklistSyncBus.publish()
            refresh()
        }
    }

    /** An Allow for a malware or phishing host, waiting for the user to confirm the warning. */
    private val _securityWarning = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val securityWarning: kotlinx.coroutines.flow.StateFlow<String?> = _securityWarning

    fun dismissSecurityWarning() { _securityWarning.value = null }

    fun addCustomRule(domain: String, allow: Boolean, confirmedRisk: Boolean = false) {
        val normalized = domain.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (normalized.isBlank()) return
        viewModelScope.launch {
            if (allow && !confirmedRisk && blocklistRepo.isSecurityThreat(normalized)) {
                _securityWarning.value = normalized
                return@launch
            }
            _securityWarning.value = null
            val rule = if (allow) {
                val app = getApplication<android.app.Application>()
                FilterRule.allowDomain(normalized, label = app.getString(
                    if (confirmedRisk) com.privacyguard.app.R.string.rule_allow_domain_risky else com.privacyguard.app.R.string.rule_allow_domain, normalized))
                    .copy(overridesSecurity = confirmedRisk)
            } else {
                // HIGH_PRIORITY like every user rule: blockDomain's default (meant for list
                // rules) made this Block lose to any matching user Allow, even a broader one.
                FilterRule.blockDomain(normalized, label = getApplication<android.app.Application>().getString(com.privacyguard.app.R.string.rule_block_domain, normalized),
                    source = FilterRule.Source.USER, priority = FilterRule.HIGH_PRIORITY)
            }
            rulesRepo.addRule(rule)
            refresh()
        }
    }

    fun removeCustomRule(id: String) {
        viewModelScope.launch {
            rulesRepo.deleteRule(id)
            refresh()
        }
    }

    fun refreshBlocklists() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true)
            runCatching { BlocklistManager.updateAllBlocklists() }
            BlocklistSyncBus.publish()
            refresh()
        }
    }

    private fun List<CategoryToggleStats>.toCategoryUi(level: com.privacyguard.core.filter.FilterEngine.BlockLevel): List<AdCategoryUi> {
        val map = associateBy { it.category.lowercase() }
        val categoryMeta = listOf(
            Triple("ads", str(com.privacyguard.app.R.string.ads_cat_ads), str(com.privacyguard.app.R.string.ads_cat_ads_desc)),
            Triple("trackers", str(com.privacyguard.app.R.string.ads_cat_trackers), str(com.privacyguard.app.R.string.ads_cat_trackers_desc)),
            Triple("malware", str(com.privacyguard.app.R.string.ads_cat_malware), str(com.privacyguard.app.R.string.ads_cat_malware_desc)),
            Triple("telemetry", str(com.privacyguard.app.R.string.ads_cat_telemetry), str(com.privacyguard.app.R.string.ads_cat_telemetry_desc)),
        )
        return categoryMeta.map { (id, title, subtitle) ->
            val stat = map[id]
            AdCategoryUi(
                id = id,
                title = title,
                subtitle = subtitle,
                totalCount = stat?.totalCount ?: 0,
                enabledCount = stat?.enabledCount ?: 0,
                isEnabled = (stat?.enabledCount ?: 0) > 0,
                usedAtLevel = AdsLevelUse.categoryUsed(id, level),
            )
        }
    }

    private fun List<SourceToggleStats>.toSourceUi(level: com.privacyguard.core.filter.FilterEngine.BlockLevel, categories: Map<String, List<String>>): List<BlocklistSourceUi> =
        map {
            BlocklistSourceUi(
                source = it.source,
                totalCount = it.totalCount,
                enabledCount = it.enabledCount,
                isEnabled = it.enabledCount > 0,
                // Unknown categories (no names stored yet): not claimed as unused.
                usedAtLevel = categories[it.source]?.let { c -> AdsLevelUse.sourceUsed(it.source, c, level) } ?: true,
            )
        }
}

/**
 * Whether the current protection level uses a list or category: the Ads screen said "On"
 * for every list with names in it, also Strict-only lists at Standard (UX audit). Reads
 * [com.privacyguard.core.filter.ProtectionLevels], the rules the filter itself uses, and
 * changes nothing; the DNS-bypass lists are used at every level (layer 2).
 */
internal object AdsLevelUse {
    private val everyLevel = setOf(
        com.privacyguard.app.data.repository.BlocklistRepo.CAT_DOH,
        com.privacyguard.app.data.repository.BlocklistRepo.CAT_DOH_IP,
    )

    fun categoryUsed(category: String, level: com.privacyguard.core.filter.FilterEngine.BlockLevel): Boolean =
        category.lowercase() in everyLevel || category.lowercase() in com.privacyguard.core.filter.ProtectionLevels.categoriesFor(level)

    fun sourceUsed(source: String, categories: Collection<String>, level: com.privacyguard.core.filter.FilterEngine.BlockLevel): Boolean =
        source !in com.privacyguard.core.filter.ProtectionLevels.excludedSourcesFor(level) &&
            categories.any { categoryUsed(it, level) }
}
