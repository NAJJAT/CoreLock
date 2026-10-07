package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per (domain, list). A domain on several lists has a row for each, so
 * refreshing or switching off one list never removes it from the others; the
 * blocked set is the distinct enabled domains. Keep in sync with
 * AppDatabase.MIGRATION_6_7.
 */
@Entity(
    tableName = "blocklist",
    indices = [
        Index(value = ["domain", "source"], unique = true),
        Index(value = ["source"]),
    ]
)
data class BlocklistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain:      String,
    val source:      String,
    val category:    String,
    val lastUpdated: Long    = System.currentTimeMillis(),
    val isEnabled:   Boolean = true,
)
