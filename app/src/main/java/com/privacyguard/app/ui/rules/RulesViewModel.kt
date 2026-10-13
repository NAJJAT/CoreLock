package com.privacyguard.app.ui.rules

import android.app.Application
import com.privacyguard.app.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConfigDatabase
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
    val candidate: RuleSuggestions.Candidate,
)

class RulesViewModel(app: Application) : AndroidViewModel(app) {
    private fun str(id: Int, vararg a: Any): String = getApplication<Application>().getString(id, *a)
    private fun plural(id: Int, n: Int): String = getApplication<Application>().resources.getQuantityString(id, n, n)

    private val db     by lazy { AppDatabase.getInstance(app) }
    private val configDb by lazy { ConfigDatabase.getInstance(app) }
    private val engine by lazy { FilterEngine() }
    private val repo   by lazy { RulesRepo(configDb.rulesDao(), engine) }
    private val prefs  by lazy { com.privacyguard.app.data.local.preferences.SettingsPreferences.getInstance(app) }

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
        val dao = db.connectionDao()
        // Whole 7 days, counted in the database; a sample of recent rows missed quiet apps.
        val cleartext = runCatching { dao.cleartextSince(since) }.getOrDefault(emptyList())
            .filter { com.privacyguard.app.ui.traffic.HomeEvidence.isInternetCleartext(it) && it.packageName.isNotBlank() }
        val background = runCatching { dao.backgroundCountsSince(since) }.getOrDefault(emptyList())
        val candidates = RuleSuggestions.build(
            cleartextByPackage = cleartext.groupingBy { it.packageName }.eachCount(),
            backgroundByPackage = background.associate { it.packageName to it.count },
            rules = _allRules.value,
            dismissed = prefs.dismissedRuleSuggestions(),
        )
        _suggestions.value = candidates.map { c ->
            val name = com.privacyguard.app.ui.traffic.AppNames.label(getApplication(), c.packageName, cleartext.firstOrNull { it.packageName == c.packageName }?.appName)
            when (c.kind) {
                RuleSuggestions.Kind.CLEARTEXT -> SuggestedRule(c.id, str(R.string.rule_block_cleartext_app, name), plural(R.plurals.rules_reason_cleartext, c.count), FilterRule.Action.DENY, c)
                RuleSuggestions.Kind.BACKGROUND -> SuggestedRule(c.id, str(R.string.rule_block_background_app, name), plural(R.plurals.rules_reason_background, c.count), FilterRule.Action.DENY, c)
            }
        }
    }

    fun acceptSuggestion(s: SuggestedRule) {
        viewModelScope.launch {
            val rule = RuleSuggestions.ruleFor(s.candidate, id = UUID.randomUUID().toString(), label = s.label)
            repo.upsertRule(rule)
            _suggestions.value = _suggestions.value.filter { it.id != s.id }
            refresh()
        }
    }

    fun dismissSuggestion(id: String) {
        prefs.dismissRuleSuggestion(id)
        _suggestions.value = _suggestions.value.filter { it.id != id }
    }

    private val blocklistRepo by lazy {
        com.privacyguard.app.data.repository.BlocklistRepo(db.blocklistDao(), configDb.blocklistToggleDao())
    }

    /** An Allow for a domain on a malware or phishing list, waiting for the user to confirm the warning. */
    private val _securityWarning = MutableStateFlow<String?>(null)
    val securityWarning: StateFlow<String?> = _securityWarning.asStateFlow()

    fun dismissSecurityWarning() { _securityWarning.value = null }

    /**
     * Allow overrides ad, tracker and telemetry lists. For a malware or phishing host
     * it first asks ([securityWarning]); [confirmedRisk] is the user's answer.
     */
    fun addDomainRule(domain: String, action: FilterRule.Action, confirmedRisk: Boolean = false) {
        val normalized = domain.trim().lowercase()
        viewModelScope.launch {
            if (action == FilterRule.Action.ALLOW && !confirmedRisk && blocklistRepo.isSecurityThreat(normalized)) {
                _securityWarning.value = normalized
                return@launch
            }
            _securityWarning.value = null
            val overrides = action == FilterRule.Action.ALLOW && confirmedRisk
            val rule = FilterRule(
                id       = UUID.randomUUID().toString(),
                label    = str(when {
                    action == FilterRule.Action.DENY -> R.string.rule_block_domain
                    overrides -> R.string.rule_allow_domain_risky
                    else -> R.string.rule_allow_domain
                }, normalized),
                action   = action,
                source   = FilterRule.Source.USER,
                priority = FilterRule.HIGH_PRIORITY,
                matchDomain = normalized,
                overridesSecurity = overrides,
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
                label    = str(if (action == FilterRule.Action.DENY) R.string.rule_block_domain else R.string.rule_allow_domain, trimmed),
                action   = action,
                source   = FilterRule.Source.USER,
                priority = FilterRule.HIGH_PRIORITY,
                matchIp  = trimmed,
            )
            repo.upsertRule(rule)
            refresh()
        }
    }

    companion object {
        /** IPv4 address or CIDR range. Rules cannot match IPv6 yet, so the dialog says so. */
        fun isValidIpOrCidr(value: String): Boolean {
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
    }

    fun addPackageRule(pkg: String, action: FilterRule.Action) {
        viewModelScope.launch {
            val trimmed = pkg.trim()
            val rule = FilterRule(
                id       = if (action == FilterRule.Action.DENY) AppsViewModel.packageBlockRuleId(trimmed)
                           else UUID.randomUUID().toString(),
                // The app's name ("WhatsApp"), not the package, as on every other screen.
                label    = str(if (action == FilterRule.Action.DENY) R.string.rule_block_app else R.string.rule_allow_app,
                    com.privacyguard.app.ui.traffic.AppNames.label(getApplication(), trimmed, null)),
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


