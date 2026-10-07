package com.privacyguard.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlocklistToggleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(toggle: BlocklistToggleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(toggles: List<BlocklistToggleEntity>)

    @Query("SELECT name FROM blocklist_toggles WHERE scope = :scope AND enabled = 0")
    suspend fun disabled(scope: String): List<String>

    @Query("DELETE FROM blocklist_toggles")
    suspend fun deleteAll()
}
