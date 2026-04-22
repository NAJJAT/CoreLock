/**
 * RulesRepo.kt
 * 
 * Repository for firewall rules
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.data.repository

import com.privacyguard.app.core.filter.FilterRule
import com.privacyguard.app.core.filter.RuleAction
import com.privacyguard.app.core.filter.RuleType
import com.privacyguard.app.data.db.RuleEntity
import com.privacyguard.app.data.db.RulesDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RulesRepository(
    private val rulesDao: RulesDao
) {
    
    /**
     * Adds a new rule
     */
    suspend fun addRule(rule: FilterRule) {
        val entity = RuleEntity(
            id = rule.id,
            type = rule.type.name,
            value = rule.value,
            action = rule.action.name,
            enabled = rule.enabled,
            priority = rule.priority,
            description = rule.description,
            createdAt = rule.createdAt,
            lastModified = rule.lastModified,
            hitCount = rule.hitCount,
            lastHit = rule.lastHit
        )
        rulesDao.insert(entity)
    }
    
    /**
     * Adds multiple rules
     */
    suspend fun addRules(rules: List<FilterRule>) {
        val entities = rules.map { rule ->
            RuleEntity(
                id = rule.id,
                type = rule.type.name,
                value = rule.value,
                action = rule.action.name,
                enabled = rule.enabled,
                priority = rule.priority,
                description = rule.description,
                createdAt = rule.createdAt,
                lastModified = rule.lastModified,
                hitCount = rule.hitCount,
                lastHit = rule.lastHit
            )
        }
        rulesDao.insertAll(entities)
    }
    
    /**
     * Gets all rules as Flow
     */
    fun getAllRulesFlow(): Flow<List<FilterRule>> {
        return flow {
            emit(rulesDao.getAllRules().map { it.toFilterRule() })
        }
    }
    
    /**
     * Gets all rules (suspend)
     */
    suspend fun getAllRules(): List<FilterRule> {
        return rulesDao.getAllRules().map { it.toFilterRule() }
    }
    
    /**
     * Gets enabled rules
     */
    suspend fun getEnabledRules(): List<FilterRule> {
        return rulesDao.getEnabledRules().map { it.toFilterRule() }
    }
    
    /**
     * Gets rules by type
     */
    suspend fun getRulesByType(type: RuleType): List<FilterRule> {
        return rulesDao.getRulesByType(type.name).map { it.toFilterRule() }
    }
    
    /**
     * Updates a rule
     */
    suspend fun updateRule(rule: FilterRule) {
        val entity = RuleEntity(
            id = rule.id,
            type = rule.type.name,
            value = rule.value,
            action = rule.action.name,
            enabled = rule.enabled,
            priority = rule.priority,
            description = rule.description,
            createdAt = rule.createdAt,
            lastModified = System.currentTimeMillis(),
            hitCount = rule.hitCount,
            lastHit = rule.lastHit
        )
        rulesDao.update(entity)
    }
    
    /**
     * Deletes a rule
     */
    suspend fun deleteRule(ruleId: String) {
        rulesDao.deleteById(ruleId)
    }
    
    /**
     * Enables or disables a rule
     */
    suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        rulesDao.setRuleEnabled(ruleId, enabled)
    }
    
    /**
     * Records a rule hit
     */
    suspend fun recordRuleHit(ruleId: String) {
        rulesDao.incrementHitCount(ruleId)
    }
    
    /**
     * Deletes all rules
     */
    suspend fun deleteAllRules() {
        rulesDao.deleteAll()
    }
    
    /**
     * Imports rules from JSON
     */
    suspend fun importRules(rules: List<FilterRule>) {
        deleteAllRules()
        addRules(rules)
    }
    
    /**
     * Exports rules to JSON
     */
    suspend fun exportRules(): String {
        val rules = getAllRules()
        return rules.joinToString(",\n") { rule ->
            """
            {
              "id": "${rule.id}",
              "type": "${rule.type.name}",
              "value": "${rule.value}",
              "action": "${rule.action.name}",
              "enabled": ${rule.enabled},
              "priority": ${rule.priority},
              "description": "${rule.description.replace("\"", "\\\"")}"
            }
            """.trimIndent()
        }
    }
}

/**
 * Converts database entity to FilterRule
 */
fun RuleEntity.toFilterRule(): FilterRule {
    return FilterRule(
        id = id,
        type = RuleType.valueOf(type),
        value = value,
        action = RuleAction.valueOf(action),
        enabled = enabled,
        priority = priority,
        description = description,
        createdAt = createdAt,
        lastModified = lastModified,
        hitCount = hitCount,
        lastHit = lastHit
    )
}
