package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocklist",
    indices = [Index(value = ["domain"], unique = true)]
)
data class BlocklistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain:      String,
    val source:      String,
    val category:    String,
    val lastUpdated: Long    = System.currentTimeMillis(),
    val isEnabled:   Boolean = true,
)
