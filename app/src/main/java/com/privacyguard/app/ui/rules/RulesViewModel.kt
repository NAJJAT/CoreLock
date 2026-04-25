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
import kotlinx.coroutines.launch
import java.util.UUID

class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val db     by lazy { AppDatabase.getInstance(app) }
    private val engine by lazy { FilterEngine() }
    private val repo   by lazy { RulesRepo(db.rulesDao(), engine) }

    private val _rules = MutableStateFlow<List<FilterRule>>(emptyList())
    val rules: StateFlow<List<FilterRule>> = _rules.asStateFlow()

    init { refresh() }

    private fun refresh() {
        viewModelScope.launch {
            _rules.value = repo.allRules()
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
            val rule = FilterRule(
                id       = UUID.randomUUID().toString(),
                label    = "${if (action == FilterRule.Action.DENY) "Block" else "Allow"} $pkg",
                action   = action,
                source   = FilterRule.Source.USER,
                priority = FilterRule.HIGH_PRIORITY,
                matchPackage = pkg.trim(),
            )
            repo.addRule(rule)
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
