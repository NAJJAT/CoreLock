package com.privacyguard.core.metadata

/**
 * A behavioral profile for a single (app, destination) pair.
 * Built by [MetadataEngine] from observed connection history.
 * No payload content is ever stored — purely metadata.
 */
data class ConnectionProfile(
    val packageName:           String,
    val hostname:              String,
    val destinationIp:         String,
    val destinationPort:       Int,
    val connectionCount:       Long         = 0L,
    val totalBytesOut:         Long         = 0L,
    val totalBytesIn:          Long         = 0L,
    val avgBytesPerConnection: Long         = 0L,
    val firstSeen:             Long         = System.currentTimeMillis(),
    val lastSeen:              Long         = System.currentTimeMillis(),
    val avgIntervalMs:         Long         = 0L,
    val minIntervalMs:         Long         = Long.MAX_VALUE,
    val backgroundRatio:       Float        = 0f,
    val hourlyDistribution:    IntArray     = IntArray(24),
    val encryptionStatus:      EncryptionStatus = EncryptionStatus.UNKNOWN,
    val tlsVersion:            TlsVersion?  = null,
    val sniHostname:           String?      = null,
    val riskScore:             Int          = 0,
    val riskSignals:           List<RiskSignal> = emptyList(),
)

data class RiskSignal(
    val code:        RiskCode,
    val description: String,
    val severity:    Int,
)
