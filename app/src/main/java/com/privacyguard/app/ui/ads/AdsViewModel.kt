package com.privacyguard.app.ui.ads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.core.blocklist.BlocklistManager
import com.privacyguard.app.core.tracker.TrackerDatabase
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.CategoryToggleStats
import com.privacyguard.app.data.db.SourceToggleStats
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
    val timeSavedMs: Long = 0L,
    val categories: List<AdCategoryUi> = emptyList(),
    val sources: List<BlocklistSourceUi> = emptyList(),
    val customRules: List<CustomDomainRuleUi> = emptyList(),
    val totalDomainsLoaded: Int = 0,
    val isRefreshing: Boolean = false,
    val topTrackerApps: List<TrackerAppUi> = emptyList(),
)

class AdsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val rulesRepo = RulesRepo(db.rulesDao(), FilterEngine())
    private val blocklistDao = db.blocklistDao()
    private val connectionDao = db.connectionDao()

    private val _state = MutableStateFlow(AdsState())
    val state: StateFlow<AdsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sourceStats = runCatching { blocklistDao.getSourceToggleStats() }.getOrDefault(emptyList())
            val categoryStats = runCatching { blocklistDao.getCategoryToggleStats() }.getOrDefault(emptyList())
            val userRules = runCatching { rulesRepo.userRules() }.getOrDefault(emptyList())
            val since = System.currentTimeMillis() - 86_400_000L
            val blockedToday = runCatching { connectionDao.getBlockedCountToday(since) }.getOrDefault(0)
            val totalToday = runCatching { connectionDao.getCount() }.getOrDefault(0)

            val recentConns = runCatching {
                connectionDao.getRecentConnections(since, 1_000)
            }.getOrDefault(emptyList())
            val topTrackerApps = recentConns
                .filter { conn ->
                    val host = conn.sniHostname ?: conn.domain ?: return@filter false
                    TrackerDatabase.lookupByDomain(host) != null
                }
                .groupBy { it.packageName }
                .filter { it.key.isNotBlank() }
                .map { (pkg, conns) ->
                    TrackerAppUi(
                        appName = conns.firstOrNull { it.appName.isNotBlank() }?.appName
                            ?: pkg.substringAfterLast('.'),
                        packageName = pkg,
                        trackerHits = conns.size,
                    )
                }
                .sortedByDescending { it.trackerHits }
                .take(5)

            _state.value = AdsState(
                masterEnabled = sourceStats.any { it.enabledCount > 0 } || categoryStats.any { it.enabledCount > 0 },
                blockedToday = blockedToday,
                blockRate = if (totalToday == 0) 0 else ((blockedToday * 100f) / totalToday).toInt(),
                timeSavedMs = blockedToday * 340L,
                categories = categoryStats.toCategoryUi(),
                sources = sourceStats.toSourceUi(),
                customRules = userRules
                    .filter { !it.matchDomain.isNullOrBlank() }
                    .map { CustomDomainRuleUi(it.id, it.matchDomain.orEmpty(), it.action == FilterRule.Action.ALLOW) },
                totalDomainsLoaded = sourceStats.sumOf { it.enabledCount },
                isRefreshing = false,
                topTrackerApps = topTrackerApps,
            )
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            blocklistDao.setAllEnabled(enabled)
            BlocklistSyncBus.publish()
            refresh()
        }
    }

    fun setCategoryEnabled(category: String, enabled: Boolean) {
        viewModelScope.launch {
            blocklistDao.setCategoryEnabled(category, enabled)
            BlocklistSyncBus.publish()
            refresh()
        }
    }

    fun setSourceEnabled(source: String, enabled: Boolean) {
        viewModelScope.launch {
            blocklistDao.setSourceEnabled(source, enabled)
            BlocklistSyncBus.publish()
            refresh()
        }
    }

    fun addCustomRule(domain: String, allow: Boolean) {
        val normalized = domain.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (normalized.isBlank()) return
        viewModelScope.launch {
            val rule = if (allow) {
                FilterRule.allowDomain(normalized, label = "Allow $normalized")
            } else {
                FilterRule.blockDomain(normalized, label = "Block $normalized", source = FilterRule.Source.USER)
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
            Triple("ads", "Display ads", "DNS and content network ad domains"),
            Triple("trackers", "Tracking scripts", "Behavioral and analytics endpoints"),
            Triple("malware", "Malware & phishing", "Known malicious and phishing infrastructure"),
            Triple("telemetry", "Telemetry", "Background telemetry and reporting endpoints"),
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
