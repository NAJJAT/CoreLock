package com.privacyguard.app.data.repository

import com.privacyguard.app.data.db.RuleEntity
import com.privacyguard.app.data.db.RulesDao

/**
 * Stub rules repository using old-style DB schema.
 * The real rules logic is in [com.privacyguard.data.repository.RulesRepo].
 */
class RulesRepository(private val rulesDao: RulesDao) {

    suspend fun getAllRules(): List<RuleEntity> = rulesDao.getAllRules()

    suspend fun deleteAllRules() = rulesDao.deleteAll()

    suspend fun deleteRule(ruleId: String) = rulesDao.deleteById(ruleId)

    suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) =
        rulesDao.setRuleEnabled(ruleId, enabled)
}
