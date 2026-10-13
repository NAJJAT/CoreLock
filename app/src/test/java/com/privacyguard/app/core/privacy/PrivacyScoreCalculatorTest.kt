package com.privacyguard.app.core.privacy

import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.core.metadata.ConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyScoreCalculatorTest {

    private fun conn(status: String, blocked: Boolean = false, background: Boolean = false) =
        ConnectionEntity(appUid = 1, encryptionStatus = status, wasBlocked = blocked, wasBackground = background)

    private val safeProfile = ConnectionProfile(packageName = "com.safe", hostname = "api.safe", destinationIp = "1.1.1.1", destinationPort = 443, riskScore = 10)

    @Test
    fun scores_secure_device_higher_than_cleartext_heavy_device() {
        val secure = PrivacyScoreCalculator.calculate(
            connections = List(3) { conn("TLS_1_3") },
            profiles = listOf(safeProfile),
            blocklistDomains = 250_000,
        )
        val weak = PrivacyScoreCalculator.calculate(
            connections = listOf(conn("CLEARTEXT"), conn("CLEARTEXT"), conn("WEAK_TLS")),
            profiles = listOf(
                ConnectionProfile(packageName = "com.risky", hostname = "bad.host", destinationIp = "2.2.2.2", destinationPort = 80, riskScore = 90)
            ),
            blocklistDomains = 1_000,
        )
        assertTrue(secure.score > weak.score)
    }

    @Test
    fun blocked_attempts_never_lower_the_score() {
        val base = List(10) { conn("TLS_1_3") }
        val without = PrivacyScoreCalculator.calculate(base, listOf(safeProfile), 100_000)
        val withBlocks = PrivacyScoreCalculator.calculate(base + List(500) { conn("UNKNOWN", blocked = true) }, listOf(safeProfile), 100_000)
        assertEquals(without.score, withBlocks.score)
    }

    @Test
    fun unknown_and_quic_are_not_cleartext() {
        val s = PrivacyScoreCalculator.calculate(
            listOf(conn("TLS_1_3"), conn("QUIC"), conn("UNKNOWN"), conn("UNKNOWN")),
            listOf(safeProfile), 250_000,
        )
        assertEquals(0, s.cleartextConnections)
        assertEquals(1f, s.encryptedRatio)
        assertEquals(2, s.encryptedConnections)
    }

    @Test
    fun the_factors_add_up_to_the_score() {
        val s = PrivacyScoreCalculator.calculate(
            listOf(conn("TLS_1_3"), conn("CLEARTEXT", background = true), conn("QUIC")),
            listOf(safeProfile), 60_000,
        )
        assertEquals(s.score, s.factors.sumOf { it.points })
        assertEquals(100, s.factors.sumOf { it.maxPoints })
    }
}
