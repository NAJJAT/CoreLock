package com.privacyguard.app.core.detection

import com.privacyguard.core.metadata.ConnectionProfile
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerwareDetectorTest {
    @Test
    fun flags_hidden_background_location_app() {
        val hourly = IntArray(24).also { it[1] = 4; it[2] = 3; it[3] = 3 }
        val assessment = StalkerwareDetector.assess(
            requestedPermissions = setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_FINE_LOCATION",
            ),
            profiles = listOf(
                ConnectionProfile(
                    packageName = "com.spy.hidden",
                    hostname = "suspicious.example",
                    destinationIp = "203.0.113.9",
                    destinationPort = 443,
                    connectionCount = 12,
                    totalBytesOut = 2_500_000L,
                    backgroundRatio = 0.95f,
                    hourlyDistribution = hourly,
                )
            ),
            hasLauncherIcon = false,
            installerPackage = null,
        )

        assertTrue(assessment.score >= 70)
        assertTrue(assessment.reasons.isNotEmpty())
    }

    @Test
    fun ordinaryStoreMessengerScoresLow() {
        val assessment = StalkerwareDetector.assess(
            requestedPermissions = setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.READ_CONTACTS"),
            profiles = listOf(
                ConnectionProfile(
                    packageName = "com.whatsapp", hostname = "g.whatsapp.net", destinationIp = "179.60.195.49",
                    destinationPort = 443, connectionCount = 40, totalBytesOut = 300_000L, backgroundRatio = 0.9f,
                    hourlyDistribution = IntArray(24) { 2 },
                )
            ),
            hasLauncherIcon = true,
            installerPackage = "com.android.vending",
        )
        assertTrue("score was ${assessment.score}", assessment.score < 30)
    }

    @Test
    fun preinstalledSystemAppIsNotScored() {
        val assessment = StalkerwareDetector.assess(
            requestedPermissions = setOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
            profiles = emptyList(),
            hasLauncherIcon = true,
            installerPackage = null,
            isSystemApp = true,
        )
        assertTrue(assessment.score == 0)
    }
}
