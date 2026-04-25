package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dns_anomalies",
    indices = [Index(value = ["timestamp"]), Index(value = ["packageName"])]
)
data class DnsAnomalyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val packageName: String,
    val domain: String,
    val anomalyType: String,
    val description: String,
    val severity: Int,
)