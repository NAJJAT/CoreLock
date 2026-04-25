package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.*
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.metadata.EncryptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RulesRepo(
    private val rulesDao: RulesDao,
    private val filterEngine: FilterEngine,
) {

    suspend fun loadIntoEngine() = withContext(Dispatchers.IO) {
        filterEngine.setRules(rulesDao.getEnabledRules().map { it.toFilterRule() })
    }

    suspend fun addRule(rule: FilterRule) = withContext(Dispatchers.IO) {
        rulesDao.insert(rule.toRuleEntity())
        filterEngine.addRule(rule)
        RuleSyncBus.publish()
    }

    suspend fun deleteRule(id: String) = withContext(Dispatchers.IO) {
        rulesDao.deleteById(id)
        filterEngine.removeRule(id)
        RuleSyncBus.publish()
    }

    suspend fun setEnabled(id: String, on: Boolean) = withContext(Dispatchers.IO) {
        rulesDao.setRuleEnabled(id, on)
        filterEngine.setEnabled(id, on)
        RuleSyncBus.publish()
    }

    suspend fun upsertRule(rule: FilterRule) = withContext(Dispatchers.IO) {
        rulesDao.insert(rule.toRuleEntity())
        filterEngine.removeRule(rule.id)
        filterEngine.addRule(rule)
        RuleSyncBus.publish()
    }

    suspend fun allRules(): List<FilterRule> = withContext(Dispatchers.IO) {
        rulesDao.getAllRules().map { it.toFilterRule() }
    }

    suspend fun userRules(): List<FilterRule> = withContext(Dispatchers.IO) {
        rulesDao.getRulesByType("USER").map { it.toFilterRule() }
    }

    suspend fun activeDenyCount(): Int = withContext(Dispatchers.IO) {
        rulesDao.getEnabledRuleCount()
    }

    private fun FilterRule.toRuleEntity() = RuleEntity(
        id = id,
        type = source.name,
        value = matchPackage ?: matchDomain ?: matchIp ?: matchEncryption?.name ?: "",
        matchUid = matchUid,
        matchPackage = matchPackage,
        matchDomain = matchDomain,
        matchIp = matchIp,
        matchPort = matchPort,
        matchProtocol = matchProtocol?.name,
        matchEncryption = matchEncryption?.name,
        action = action.name,
        enabled = isEnabled,
        priority = priority,
        description = label,
    )

    private fun RuleEntity.toFilterRule() = FilterRule(
        id = id,
        label = description,
        action = FilterRule.Action.valueOf(action),
        source = FilterRule.Source.valueOf(type),
        priority = priority,
        isEnabled = enabled,
        matchUid = matchUid,
        matchPackage = matchPackage,
        matchDomain = matchDomain ?: value.takeIf { it.isNotBlank() && matchPackage.isNullOrBlank() && matchIp.isNullOrBlank() },
        matchIp = matchIp,
        matchPort = matchPort,
        matchProtocol = matchProtocol?.let(FilterRule.Protocol::valueOf),
        matchEncryption = matchEncryption?.let(EncryptionStatus::valueOf),
    )
}
