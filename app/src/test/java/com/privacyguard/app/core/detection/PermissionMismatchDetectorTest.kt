package com.privacyguard.app.core.detection

import com.privacyguard.app.core.tracker.TrackerCategory
import com.privacyguard.app.core.tracker.TrackerEntry
import com.privacyguard.core.metadata.ConnectionProfile
import com.privacyguard.core.metadata.EncryptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionMismatchDetectorTest {

    @Test
    fun flags_location_style_hosts_without_location_permission() {
        val findings = PermissionMismatchDetector.analyze(
            appName = "Simple Notes",
            packageName = "com.example.notes",
            requestedPermissions = setOf("android.permission.INTERNET"),
            observedDomains = listOf("www.googleapis.com", "geoip.example.net", "maps.googleapis.com"),
            detectedSdks = emptyList(),
            profiles = emptyList(),
        )

        assertTrue(findings.any { it.id == "location_without_permission" })
    }

    @Test
    fun flags_utility_app_with_tracker_stack() {
        val findings = PermissionMismatchDetector.analyze(
            appName = "Flashlight Pro",
            packageName = "com.example.flashlight",
            requestedPermissions = setOf("android.permission.INTERNET"),
            observedDomains = listOf("graph.facebook.com"),
            detectedSdks = listOf(
                TrackerEntry(
                    name = "Facebook SDK",
                    company = "Meta Platforms",
                    category = TrackerCategory.SOCIAL,
                    domains = listOf("graph.facebook.com"),
                )
            ),
            profiles = listOf(
                ConnectionProfile(
                    packageName = "com.example.flashlight",
                    hostname = "graph.facebook.com",
                    destinationIp = "157.240.22.35",
                    destinationPort = 443,
                    connectionCount = 6,
                    totalBytesOut = 300_000L,
                    totalBytesIn = 400_000L,
                    encryptionStatus = EncryptionStatus.UNKNOWN,
                )
            ),
        )

        assertTrue(findings.any { it.id == "utility_tracker_stack" })
        assertTrue(findings.any { it.id == "social_identity_without_contacts" })
    }

    @Test
    fun flags_background_upload_pattern_for_low_capability_utility_app() {
        val hourly = IntArray(24)
        hourly[1] = 3
        hourly[2] = 2
        hourly[3] = 1

        val findings = PermissionMismatchDetector.analyze(
            appName = "Calculator Lite",
            packageName = "com.example.calculator",
            requestedPermissions = setOf("android.permission.INTERNET"),
            observedDomains = listOf("api.example.net"),
            detectedSdks = emptyList(),
            profiles = listOf(
                ConnectionProfile(
                    packageName = "com.example.calculator",
                    hostname = "api.example.net",
                    destinationIp = "203.0.113.5",
                    destinationPort = 443,
                    connectionCount = 8,
                    totalBytesOut = 3_100_000L,
                    totalBytesIn = 120_000L,
                    backgroundRatio = 0.92f,
                    hourlyDistribution = hourly,
                )
            ),
        )

        val finding = findings.firstOrNull { it.id == "background_upload_mismatch" }
        assertEquals(MismatchSeverity.HIGH, finding?.severity)
    }
}
