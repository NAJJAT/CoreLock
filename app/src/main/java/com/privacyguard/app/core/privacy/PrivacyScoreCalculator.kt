package com.privacyguard.app.core.privacy

import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.repository.MetadataRepo
import com.privacyguard.app.ui.traffic.HomeEvidence
import com.privacyguard.core.metadata.ConnectionProfile
import com.privacyguard.core.metadata.EncryptionStatus
import kotlin.math.roundToInt

/** One part of the score: how many of its [maxPoints] it earned, and from what. */
data class ScoreFactor(
    val kind: Kind,
    /** 0..1, higher is better. */
    val fraction: Float,
    val points: Int,
    val maxPoints: Int,
) {
    enum class Kind { ENCRYPTION, HIGH_RISK_APPS, BLOCKLIST, BACKGROUND }
}

data class PrivacyScoreBreakdown(
    val score: Int,
    /** Encrypted share of connections whose encryption we could see. */
    val encryptedRatio: Float,
    val highRiskApps: Int,
    val cleartextConnections: Int,
    val blocklistCoverage: Float,
    val backgroundRatio: Float = 0f,
    val factors: List<ScoreFactor> = emptyList(),
    /** Connections the score is based on (those that went through). */
    val connections: Int = 0,
    val encryptedConnections: Int = 0,
)

/**
 * The one privacy score: Home, Stats, the weekly report and the IT report all call
 * this, so they always show the same number for the same window.
 *
 *  - Encryption (50): of the connections whose encryption we could see (a TLS or
 *    QUIC handshake, or a plain HTTP request), the share that was encrypted.
 *    "Unknown" connections are left out rather than guessed.
 *  - High-risk apps (25): minus 2.5 points per app with a profile scoring 70+, max 10.
 *  - Blocklist coverage (15): how many domains the current protection level blocks.
 *  - Background (10): the share of connections made while the app was off screen.
 *
 * Blocked lookups are deliberately not an input: blocking more must never lower the
 * score (it used to, through a "tracker density" term).
 */
object PrivacyScoreCalculator {

    private const val W_ENCRYPTION = 50
    private const val W_HIGH_RISK = 25
    private const val W_BLOCKLIST = 15
    private const val W_BACKGROUND = 10

    fun calculate(
        connections: List<ConnectionEntity>,
        profiles: List<ConnectionProfile>,
        blocklistDomains: Int,
    ): PrivacyScoreBreakdown {
        val allowed = connections.filter { !it.wasBlocked }
        val coverage = coverageScore(blocklistDomains)
        if (allowed.isEmpty() && profiles.isEmpty()) {
            // Nothing observed yet: neutral, and only coverage is known.
            return PrivacyScoreBreakdown(
                score = 50, encryptedRatio = 0f, highRiskApps = 0, cleartextConnections = 0,
                blocklistCoverage = coverage,
            )
        }

        val share = HomeEvidence.encryptionShare(allowed)
        val encrypted = share.encrypted
        val cleartext = share.cleartext
        val encryptedRatio = share.ratio
        val highRiskApps = profiles.groupBy { it.packageName }
            .count { (_, rows) -> (rows.maxOfOrNull { it.riskScore } ?: 0) >= 70 }
        val backgroundRatio = if (allowed.isEmpty()) 0f else allowed.count { it.wasBackground }.toFloat() / allowed.size

        val factors = listOf(
            factor(ScoreFactor.Kind.ENCRYPTION, encryptedRatio, W_ENCRYPTION),
            factor(ScoreFactor.Kind.HIGH_RISK_APPS, 1f - (highRiskApps / 10f).coerceIn(0f, 1f), W_HIGH_RISK),
            factor(ScoreFactor.Kind.BLOCKLIST, coverage, W_BLOCKLIST),
            factor(ScoreFactor.Kind.BACKGROUND, 1f - backgroundRatio, W_BACKGROUND),
        )
        return PrivacyScoreBreakdown(
            score = factors.sumOf { it.points }.coerceIn(0, 100),
            encryptedRatio = encryptedRatio,
            highRiskApps = highRiskApps,
            cleartextConnections = cleartext,
            blocklistCoverage = coverage,
            backgroundRatio = backgroundRatio,
            factors = factors,
            connections = allowed.size,
            encryptedConnections = encrypted,
        )
    }

    /** Domains the protection level [level] blocks: the "blocklist coverage" input. */
    suspend fun blockedDomainsForLevel(db: AppDatabase, level: String): Int {
        val levels = com.privacyguard.core.filter.ProtectionLevels
        val parsed = levels.parse(level)
        return db.blocklistDao().enabledCountForLevel(levels.categoriesFor(parsed).toList(), levels.excludedSourcesFor(parsed).toList())
    }

    /** Loads the inputs for the window [from]..[to] and scores it. */
    suspend fun forWindow(db: AppDatabase, from: Long, to: Long, blocklistDomains: Int): PrivacyScoreBreakdown {
        val repo = MetadataRepo(db.connectionProfileDao())
        return calculate(
            connections = db.connectionDao().between(from, to),
            profiles = db.connectionProfileDao().allProfiles().map { repo.toDomainForDashboard(it) },
            blocklistDomains = blocklistDomains,
        )
    }

    private fun factor(kind: ScoreFactor.Kind, fraction: Float, max: Int): ScoreFactor {
        val f = fraction.coerceIn(0f, 1f)
        return ScoreFactor(kind, f, (f * max).roundToInt(), max)
    }

    private fun coverageScore(blocklistDomains: Int): Float = when {
        blocklistDomains >= 250_000 -> 1f
        blocklistDomains >= 100_000 -> 0.8f
        blocklistDomains >= 50_000 -> 0.55f
        blocklistDomains > 0 -> 0.3f
        else -> 0f
    }
}
