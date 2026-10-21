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
)

data class BlocklistSourceUi(
    val source: String,
    val totalCount: Int,
    val enabledCount: Int,
    val isEnabled: Boolean,
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
            // Same definition of "blocked" as Home and Stats (BlockStats), last 24 h.
            val blocks = runCatching {
                com.privacyguard.app.data.repository.BlockStats(db).between(since, System.currentTimeMillis())
            }.getOrNull()
            val blockedToday = blocks?.total ?: 0

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
                categories = categoryStats.toCategoryUi(),
                sources = sourceStats.toSourceUi(),
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
                FilterRule.blockDomain(normalized, label = getApplication<android.app.Application>().getString(com.privacyguard.app.R.string.rule_block_domain, normalized), source = FilterRule.Source.USER)
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

    private fun List<CategoryToggleStats>.toCategoryUi(): List<AdCategoryUi> {
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
            )
        }
    }

    private fun List<SourceToggleStats>.toSourceUi(): List<BlocklistSourceUi> =
        map {
            BlocklistSourceUi(
                source = it.source,
                totalCount = it.totalCount,
                enabledCount = it.enabledCount,
                isEnabled = it.enabledCount > 0,
            )
        }
}
