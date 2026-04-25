package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connection_profiles", primaryKeys = ["packageName", "hostname"])
data class ConnectionProfileEntity(
    val packageName: String,
    val hostname: String,
    val destinationIp: String = "",
    val destinationPort: Int = 0,
    val connectionCount: Long = 0L,
    val totalBytesOut: Long = 0L,
    val totalBytesIn: Long = 0L,
    val avgBytesPerConnection: Long = 0L,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val avgIntervalMs: Long = 0L,
    val minIntervalMs: Long = Long.MAX_VALUE,
    val backgroundRatio: Float = 0f,
    val hourlyDistribution: String = "",
    val encryptionStatus: String = "UNKNOWN",
    val tlsVersion: String? = null,
    val sniHostname: String? = null,
    val riskScore: Int = 0,
    val riskSignalCodes: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)