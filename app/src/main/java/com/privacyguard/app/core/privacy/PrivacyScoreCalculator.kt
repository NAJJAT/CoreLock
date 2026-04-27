package com.privacyguard.app.core.privacy

import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.core.metadata.ConnectionProfile
import com.privacyguard.core.metadata.EncryptionStatus
import kotlin.math.roundToInt

data class PrivacyScoreBreakdown(
    val score: Int,
    val encryptedRatio: Float,
    val trackerDensity: Float,
    val highRiskApps: Int,
    val cleartextConnections: Int,
    val blocklistCoverage: Float,
    val backgroundRatio: Float = 0f,
)

object PrivacyScoreCalculator {
    fun calculate(
        connections: List<ConnectionEntity>,
        profiles: List<ConnectionProfile>,
        trackersBlocked: Int,
        blocklistDomains: Int,
    ): PrivacyScoreBreakdown {
        if (connections.isEmpty() && profiles.isEmpty()) {
            return PrivacyScoreBreakdown(
                score = 50,
                encryptedRatio = 0f,
                trackerDensity = 0f,
                highRiskApps = 0,
                cleartextConnections = 0,
                blocklistCoverage = coverageScore(blocklistDomains),
            )
        }

        val totalConnections = connections.size.coerceAtLeast(1)
        val secureConnections = connections.count {
            it.encryptionStatus == EncryptionStatus.TLS.name || it.tlsVersion?.startsWith("TLS_1_") == true
        }
        val encryptedRatio = secureConnections.toFloat() / totalConnections.toFloat()
        val cleartextConnections = connections.count {
            it.encryptionStatus == EncryptionStatus.CLEARTEXT.name || it.encryptionStatus == EncryptionStatus.UNKNOWN.name
        }
        val backgroundConnections = connections.count { it.wasBackground }
        val backgroundRatio = backgroundConnections.toFloat() / totalConnections.toFloat()
        val trackerDensity = (trackersBlocked.toFloat() / totalConnections.toFloat()).coerceIn(0f, 1f)
        val highRiskApps = profiles
            .groupBy { it.packageName }
            .count { (_, rows) -> rows.maxOfOrNull { it.riskScore } ?: 0 >= 70 }
        val blocklistCoverage = coverageScore(blocklistDomains)

        // Background ratio penalty: deduct up to 5 pts when >50% of connections are background
        val backgroundPenalty = (backgroundRatio * 5f).coerceIn(0f, 5f)

        val score = (
            encryptedRatio * 38f +
                (1f - trackerDensity) * 20f +
                (1f - (cleartextConnections.toFloat() / totalConnections.toFloat()).coerceIn(0f, 1f)) * 20f +
                (1f - (highRiskApps / 10f).coerceIn(0f, 1f)) * 10f +
                blocklistCoverage * 10f +
                (5f - backgroundPenalty) * 0.4f
            ).roundToInt().coerceIn(0, 100)

        return PrivacyScoreBreakdown(
            score = score,
            encryptedRatio = encryptedRatio,
            trackerDensity = trackerDensity,
            highRiskApps = highRiskApps,
            cleartextConnections = cleartextConnections,
            blocklistCoverage = blocklistCoverage,
            backgroundRatio = backgroundRatio,
        )
    }

    private fun coverageScore(blocklistDomains: Int): Float = when {
        blocklistDomains >= 250_000 -> 1f
        blocklistDomains >= 100_000 -> 0.8f
        blocklistDomains >= 50_000 -> 0.55f
        blocklistDomains > 0 -> 0.3f
        else -> 0f
    }
}
