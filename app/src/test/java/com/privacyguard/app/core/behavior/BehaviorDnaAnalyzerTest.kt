package com.privacyguard.app.core.behavior

import com.privacyguard.core.metadata.ConnectionProfile
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorDnaAnalyzerTest {

    @Test
    fun flags_new_destination_after_baseline_is_ready() {
        val now = 1_000_000_000L
        val baseline = ConnectionProfile(
            packageName = "com.example.app",
            hostname = "api.example.com",
            destinationIp = "203.0.113.1",
            destinationPort = 443,
            connectionCount = 50,
            firstSeen = now - 9L * 24L * 60L * 60L * 1000L,
            lastSeen = now - 2L * 24L * 60L * 60L * 1000L,
            hourlyDistribution = IntArray(24).also { it[10] = 20; it[11] = 15 },
        )
        val newDest = ConnectionProfile(
            packageName = "com.example.app",
            hostname = "upload.evil.example",
            destinationIp = "198.51.100.10",
            destinationPort = 443,
            connectionCount = 4,
            firstSeen = now - 2L * 60L * 60L * 1000L,
            lastSeen = now - 1L * 60L * 60L * 1000L,
            hourlyDistribution = IntArray(24).also { it[2] = 4 },
            backgroundRatio = 0.9f,
        )

        val summary = BehaviorDnaAnalyzer.summarizeApp("com.example.app", listOf(baseline, newDest), now)

        assertTrue(summary.baselineReady)
        assertTrue(summary.findings.any { it.title.contains("New destination") })
    }

    @Test
    fun flags_upload_spike_and_beacon_pattern() {
        val now = 2_000_000_000L
        val profile = ConnectionProfile(
            packageName = "com.example.calc",
            hostname = "c2.example.net",
            destinationIp = "198.51.100.20",
            destinationPort = 443,
            connectionCount = 12,
            totalBytesOut = 4_500_000L,
            firstSeen = now - 8L * 24L * 60L * 60L * 1000L,
            lastSeen = now - 10L * 60L * 1000L,
            avgIntervalMs = 30_000L,
            backgroundRatio = 0.92f,
            hourlyDistribution = IntArray(24).also { it[3] = 10 },
        )
        val baseline = profile.copy(
            hostname = "api.example.net",
            destinationIp = "198.51.100.21",
            totalBytesOut = 120_000L,
            connectionCount = 30,
            avgIntervalMs = 0L,
            backgroundRatio = 0.2f,
            hourlyDistribution = IntArray(24).also { it[10] = 15; it[11] = 10 },
        )
        val baselineTwo = baseline.copy(
            hostname = "cdn.example.net",
            destinationIp = "198.51.100.22",
            totalBytesOut = 80_000L,
            connectionCount = 18,
            hourlyDistribution = IntArray(24).also { it[12] = 9; it[13] = 7 },
        )

        val summary = BehaviorDnaAnalyzer.summarizeApp("com.example.calc", listOf(profile, baseline, baselineTwo), now)

        assertTrue(summary.findings.any { it.title.contains("Upload spike") })
        assertTrue(summary.findings.any { it.title.contains("Background beacon") })
    }
}
