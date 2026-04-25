package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "network_trust")
data class NetworkTrustEntity(
    @PrimaryKey val networkKey: String,
    val networkLabel: String,
    val trustScore: Int,
    val trustLevel: String,
    val cleartextCount: Int,
    val dnsAnomalyCount: Int,
    val weakTlsCount: Int,
    val blockedCount: Int,
    val lastSeen: Long = System.currentTimeMillis(),
)
