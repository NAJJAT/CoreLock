package com.privacyguard.app.ui.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.repository.RulesRepo
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import com.privacyguard.app.ui.apps.AppsViewModel
import java.util.UUID

data class SuggestedRule(
    val id: String,
    val label: String,
    val reason: String,
    val action: FilterRule.Action,
    val matchPackage: String? = null,
    val matchDomain: String? = null,
)

class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val db     by lazy { AppDatabase.getInstance(app) }
    private val engine by lazy { FilterEngine() }
    private val repo   by lazy { RulesRepo(db.rulesDao(), engine) }

    private val _allRules = MutableStateFlow<List<FilterRule>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _suggestions = MutableStateFlow<List<SuggestedRule>>(emptyList())
    val suggestions: StateFlow<List<SuggestedRule>> = _suggestions.asStateFlow()

    val rules: StateFlow<List<FilterRule>> = combine(_allRules, _searchQuery) { rules, q ->
        if (q.isBlank()) rules
        else rules.filter { rule ->
            rule.label.contains(q, ignoreCase = true) ||
            rule.matchDomain?.contains(q, ignoreCase = true) == true ||
            rule.matchPackage?.contains(q, ignoreCase = true) == true ||
            rule.matchIp?.contains(q, ignoreCase = true) == true
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSearchQuery(q: String) { _searchQuery.value = q }

    init { refresh() }

    private fun refresh() {
        viewModelScope.launch {
            try {
                _allRules.value = repo.allRules()
                buildSuggestions()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: Exception) { }
        }
    }

    private suspend fun buildSuggestions() {
        val since = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000L
        val recentConns = runCatching {
            db.connectionDao().getRecentConnections(since, 600)
        }.getOrDefault(emptyList())
        val existing = _allRules.value
        val blockedPkgs = existing
            .filter { it.isEnabled && it.action == FilterRule.Action.DENY && !it.matchPackage.isNullOrBlank() }
            .mapNotNull { it.matchPackage }.toSet()
        val hasGlobalCleartextBlock = existing.any {
            it.isEnabled && it.matchEncryption != null && it.action == FilterRule.Action.DENY
        }

        val list = mutableListOf<SuggestedRule>()

        // Apps with heavy cleartext traffic not already blocked
        if (!hasGlobalCleartextBlock) {
            recentConns
                .filter { it.encryptionStatus == "CLEARTEXT" && !it.wasBlocked && it.packageName.isNotBlank() }
                .groupBy { it.packageName }
                .filter { (pkg, _) -> pkg !in blockedPkgs }
                .mapValues { (_, c) -> c.size }
                .filter { (_, n) -> n >= 3 }
                .entries.sortedByDescending { it.value }.take(3)
                .forEach { (pkg, n) ->
                    val name = recentConns.firstOrNull { it.packageName == pkg }
                        ?.appName?.ifBlank { pkg.substringAfterLast('.') } ?: pkg.substringAfterLast('.')
                    list += SuggestedRule(
                        id = "suggest:cleartext:$pkg",
                        label = "Block $name",
                        reason = "$n cleartext connections (7d)",
                        action = FilterRule.Action.DENY,
                        matchPackage = pkg,
                    )
                }
        }

        // Apps with excessive background connections not already blocked
        recentConns
            .filter { it.wasBackground && it.packageName.isNotBlank() }
            .groupBy { it.packageName }
            .filter { (pkg, _) -> pkg !in blockedPkgs }
            .mapValues { (_, c) -> c.size }
            .filter { (_, n) -> n >= 15 }
            .entries.sortedByDescending { it.value }.take(2)
            .forEach { (pkg, n) ->
                if (list.none { it.matchPackage == pkg }) {
                    val name = recentConns.firstOrNull { it.packageName == pkg }
                        ?.appName?.ifBlank { pkg.substringAfterLast('.') } ?: pkg.substringAfterLast('.')
                    list += SuggestedRule(
                        id = "suggest:background:$pkg",
                        label = "Block $name",
                        reason = "$n background connections (7d)",
                        action = FilterRule.Action.DENY,
                        matchPackage = pkg,
                    )
                }
            }

        _suggestions.value = list.take(5)
    }

    fun acceptSuggestion(s: SuggestedRule) {
        viewModelScope.launch {
            val rule = FilterRule(
                id           = s.matchPackage?.let { AppsViewModel.packageBlockRuleId(it) } ?: UUID.randomUUID().toString(),
                label        = s.label,
                action       = s.action,
                source       = FilterRule.Source.USER,
                priority     = FilterRule.HIGH_PRIORITY,
                matchPackage = s.matchPackage,
                matchDomain  = s.matchDomain,
            )
            repo.upsertRule(rule)
            _suggestions.value = _suggestions.value.filter { it.id != s.id }
            refresh()
        }
    }

    fun dismissSuggestion(id: String) {
        _suggestions.value = _suggestions.value.filter { it.id != id }
    }

    fun addDomainRule(domain: String, action: FilterRule.Action) {
        viewModelScope.launch {
            val rule = FilterRule(
                id       = UUID.randomUUID().toString(),
                label    = "${if (action == FilterRule.Action.DENY) "Block" else "Allow"} $domain",
                action   = action,
                source   = FilterRule.Source.USER,
                priority = FilterRule.HIGH_PRIORITY,
                matchDomain = domain.trim().lowercase(),
            )
            repo.addRule(rule)
            refresh()
        }
    }

    fun addIpRule(ip: String, action: FilterRule.Action) {
        viewModelScope.launch {
            val trimmed = ip.trim()
            if (!isValidIpOrCidr(trimmed)) return@launch
            val rule = FilterRule(
                id       = UUID.randomUUID().toString(),
                label    = "${if (action == FilterRule.Action.DENY) "Block" else "Allow"} $trimmed",
                action   = action,
                source   = FilterRule.Source.USER,
                priority = FilterRule.HIGH_PRIORITY,
                matchIp  = trimmed,
            )
            repo.upsertRule(rule)
            refresh()
        }
    }

    private fun isValidIpOrCidr(value: String): Boolean {
        val parts = value.split('/')
        if (parts.size !in 1..2) return false
        if (!isValidIpv4(parts[0])) return false
        val prefix = parts.getOrNull(1) ?: return true
        return prefix.toIntOrNull()?.let { it in 0..32 } == true
    }

    private fun isValidIpv4(value: String): Boolean {
        val octets = value.split('.')
        if (octets.size != 4) return false
        return octets.all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }
    }

    fun addPackageRule(pkg: String, action: FilterRule.Action) {
        viewModelScope.launch {
            val trimmed = pkg.trim()
            val rule = FilterRule(
                id       = if (action == FilterRule.Action.DENY) AppsViewModel.packageBlockRuleId(trimmed)
                           else UUID.randomUUID().toString(),
                label    = "${if (action == FilterRule.Action.DENY) "Block" else "Allow"} $trimmed",
                action   = action,
                source   = FilterRule.Source.USER,
                priority = FilterRule.HIGH_PRIORITY,
                matchPackage = trimmed,
            )
            repo.upsertRule(rule)
            refresh()
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch {
            repo.deleteRule(id)
            refresh()
        }
    }

    fun toggleRule(id: String, enabled: Boolean) {
        viewModelScope.launch {
            repo.setEnabled(id, enabled)
            refresh()
        }
    }
}


