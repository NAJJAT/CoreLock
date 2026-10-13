package com.privacyguard.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "network_trust")
data class NetworkTrustEntity(
    @PrimaryKey val networkKey: String,
    val networkLabel: String,
    val trustScore: Int,
    val trustLevel: String,
    // Network-side signals only (see NetworkTrustAnalyzer); app behaviour is not here.
    val openWifi: Boolean,
    val captivePortal: Boolean,
    val tamperedAnswers: Int,
    val dnsHijacked: Boolean,
    val tlsIntercepted: Boolean,
    val lastSeen: Long = System.currentTimeMillis(),
)
