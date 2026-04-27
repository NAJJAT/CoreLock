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

class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val db     by lazy { AppDatabase.getInstance(app) }
    private val engine by lazy { FilterEngine() }
    private val repo   by lazy { RulesRepo(db.rulesDao(), engine) }

    private val _allRules = MutableStateFlow<List<FilterRule>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

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
            _allRules.value = repo.allRules()
        }
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
