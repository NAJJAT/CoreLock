package com.privacyguard.app.core.privacy

import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.core.metadata.ConnectionProfile
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyScoreCalculatorTest {

    @Test
    fun scores_secure_device_higher_than_cleartext_heavy_device() {
        val secure = PrivacyScoreCalculator.calculate(
            connections = listOf(
                ConnectionEntity(appUid = 1, encryptionStatus = "TLS"),
                ConnectionEntity(appUid = 1, encryptionStatus = "TLS"),
                ConnectionEntity(appUid = 1, encryptionStatus = "TLS"),
            ),
            profiles = listOf(
                ConnectionProfile(packageName = "com.safe", hostname = "api.safe", destinationIp = "1.1.1.1", destinationPort = 443, riskScore = 10)
            ),
            trackersBlocked = 0,
            blocklistDomains = 250_000,
        )
        val weak = PrivacyScoreCalculator.calculate(
            connections = listOf(
                ConnectionEntity(appUid = 1, encryptionStatus = "CLEARTEXT"),
                ConnectionEntity(appUid = 1, encryptionStatus = "UNKNOWN"),
                ConnectionEntity(appUid = 1, encryptionStatus = "WEAK_TLS"),
            ),
            profiles = listOf(
                ConnectionProfile(packageName = "com.risky", hostname = "bad.host", destinationIp = "2.2.2.2", destinationPort = 80, riskScore = 90)
            ),
            trackersBlocked = 3,
            blocklistDomains = 1_000,
        )

        assertTrue(secure.score > weak.score)
    }
}
