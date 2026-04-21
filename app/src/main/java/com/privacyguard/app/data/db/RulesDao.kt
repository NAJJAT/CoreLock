/**
 * RulesDao.kt
 * 
 * Data Access Object for firewall rules
 * 
 * @author PrivacyGuard Engineering Team
 * @since 1.0.0
 */

package com.privacyguard.app.data.db

import androidx.room.*

@Dao
interface RulesDao {
    
    /**
     * Inserts a new rule
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: RuleEntity)
    
    /**
     * Inserts multiple rules
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<RuleEntity>)
    
    /**
     * Gets all rules
     */
    @Query("SELECT * FROM rules ORDER BY priority DESC, createdAt DESC")
    suspend fun getAllRules(): List<RuleEntity>
    
    /**
     * Gets enabled rules only
     */
    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY priority DESC")
    suspend fun getEnabledRules(): List<RuleEntity>
    
    /**
     * Gets rules by type
     */
    @Query("SELECT * FROM rules WHERE type = :type ORDER BY priority DESC")
    suspend fun getRulesByType(type: String): List<RuleEntity>
    
    /**
     * Gets a rule by ID
     */
    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun getRuleById(id: String): RuleEntity?
    
    /**
     * Updates a rule
     */
    @Update
    suspend fun update(rule: RuleEntity)
    
    /**
     * Deletes a rule
     */
    @Delete
    suspend fun delete(rule: RuleEntity)
    
    /**
     * Deletes a rule by ID
     */
    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteById(id: String)
    
    /**
     * Enables or disables a rule
     */
    @Query("UPDATE rules SET enabled = :enabled, lastModified = :now WHERE id = :id")
    suspend fun setRuleEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis())
    
    /**
     * Increments hit count for a rule
     */
    @Query("UPDATE rules SET hitCount = hitCount + 1, lastHit = :now WHERE id = :id")
    suspend fun incrementHitCount(id: String, now: Long = System.currentTimeMillis())
    
    /**
     * Deletes all rules
     */
    @Query("DELETE FROM rules")
    suspend fun deleteAll()
    
    /**
     * Gets rule count
     */
    @Query("SELECT COUNT(*) FROM rules")
    suspend fun getRuleCount(): Int
    
    /**
     * Gets enabled rule count
     */
    @Query("SELECT COUNT(*) FROM rules WHERE enabled = 1")
    suspend fun getEnabledRuleCount(): Int
}