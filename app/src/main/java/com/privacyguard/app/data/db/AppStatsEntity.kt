package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "app_stats",
    indices = [Index(value = ["appUid", "date"], unique = true)]
)
data class AppStatsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appUid:           Int,
    val appName:          String,
    val date:             String,  // yyyy-MM-dd
    val bytesSent:        Long    = 0L,
    val bytesReceived:    Long    = 0L,
    val packetsSent:      Int     = 0,
    val packetsReceived:  Int     = 0,
    val blockedCount:     Int     = 0,
)
