package com.privacyguard.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NetworkTrustDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NetworkTrustEntity)

    @Query("SELECT * FROM network_trust ORDER BY lastSeen DESC")
    suspend fun allNetworks(): List<NetworkTrustEntity>

    @Query("DELETE FROM network_trust")
    suspend fun deleteAll()
}
