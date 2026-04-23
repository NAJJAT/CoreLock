package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id:         String,
    val type:         String,
    val value:        String    = "",
    val action:       String,
    val enabled:      Boolean   = true,
    val priority:     Int       = 100,
    val description:  String    = "",
    val createdAt:    Long      = System.currentTimeMillis(),
    val lastModified: Long      = System.currentTimeMillis(),
    val hitCount:     Int       = 0,
    val lastHit:      Long?     = null,
)
