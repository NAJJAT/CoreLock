package com.privacyguard.app.data.db

import androidx.room.*

@Dao
interface ConnectionProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ConnectionProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<ConnectionProfileEntity>)

    @Query("SELECT * FROM connection_profiles ORDER BY lastSeen DESC")
    suspend fun allProfiles(): List<ConnectionProfileEntity>

    @Query("SELECT * FROM connection_profiles WHERE packageName = :packageName ORDER BY lastSeen DESC")
    suspend fun profilesForApp(packageName: String): List<ConnectionProfileEntity>

    @Query("SELECT * FROM connection_profiles WHERE riskScore >= :minRisk ORDER BY riskScore DESC")
    suspend fun riskyProfiles(minRisk: Int): List<ConnectionProfileEntity>

    @Query("SELECT * FROM connection_profiles WHERE encryptionStatus IN ('NONE', 'UNKNOWN') ORDER BY connectionCount DESC")
    suspend fun cleartextProfiles(): List<ConnectionProfileEntity>

    @Query("""
        SELECT * FROM connection_profiles 
        WHERE hostname LIKE '%' || :query || '%' 
           OR packageName LIKE '%' || :query || '%'
        ORDER BY lastSeen DESC
    """)
    suspend fun search(query: String): List<ConnectionProfileEntity>

    @Query("SELECT DISTINCT packageName FROM connection_profiles ORDER BY packageName")
    suspend fun trackedPackages(): List<String>

    @Query("DELETE FROM connection_profiles WHERE lastSeen < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM connection_profiles")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM connection_profiles")
    suspend fun count(): Int
}