package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appUid:           Int,
    val appName:          String    = "",
    val domain:           String?   = null,
    val destinationIp:    String    = "",
    val destinationPort:  Int       = 0,
    val protocol:         String    = "TCP",
    val wasBlocked:       Boolean   = false,
    val bytesSent:        Long      = 0L,
    val bytesReceived:    Long      = 0L,
    val timestamp:        Long      = System.currentTimeMillis(),
    val durationMs:       Long      = 0L,
)
