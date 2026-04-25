package com.privacyguard.app.core.detection

import com.privacyguard.app.core.tracker.TrackerCategory
import com.privacyguard.app.core.tracker.TrackerDatabase
import com.privacyguard.app.core.tracker.TrackerEntry
import com.privacyguard.core.metadata.ConnectionProfile
import kotlin.math.roundToInt

enum class MismatchSeverity(val label: String, val rank: Int) {
    LOW("LOW", 0),
    MEDIUM("MED", 1),
    HIGH("HIGH", 2),
}

data class PermissionMismatchFinding(
    val id: String,
    val severity: MismatchSeverity,
    val title: String,
    val summary: String,
    val evidence: List<String> = emptyList(),
)

object PermissionMismatchDetector {
    private val locationPermissions = setOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
    )

    private val contactsPermissions = setOf(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
    )

    private val dataSensitivePermissions = setOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
    )

    private val utilityTokens = setOf(
        "flashlight", "torch", "calculator", "calc", "clock", "alarm", "notes", "note",
        "scanner", "scan", "pdf", "reader", "cleaner", "booster", "qr", "barcode",
        "compass", "wallpaper", "keyboard", "weather", "translate", "recorder", "file",
        "files", "document", "docs", "gallery", "vpn", "proxy",
    )

    private val locationHostKeywords = listOf(
        "geoip", "geolocation", "location", "places", "maps", "mapbox", "tile", "tiles",
        "hereapi", "fusedlocation", "mozilla.location.services",
    )

    private val socialIdentityHosts = listOf(
        "graph.facebook.com",
        "connect.facebook.net",
        "api.twitter.com",
        "api.x.com",
        "api.linkedin.com",
        "people.googleapis.com",
        "contacts.googleapis.com",
    )

    private val trackerSensitiveCategories = setOf(
        TrackerCategory.ADVERTISING,
        TrackerCategory.ANALYTICS,
        TrackerCategory.FINGERPRINTING,
        TrackerCategory.PROFILING,
        TrackerCategory.SOCIAL,
    )

    fun analyze(
        appName: String,
        packageName: String,
        requestedPermissions: Set<String>,
        observedDomains: List<String>,
        detectedSdks: List<TrackerEntry>,
        profiles: List<ConnectionProfile>,
    ): List<PermissionMismatchFinding> {
        val normalizedDomains = observedDomains
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()

        val profileHosts = profiles
            .map { it.hostname.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()

        val allHosts = (normalizedDomains + profileHosts).distinct()
        val findings = mutableListOf<PermissionMismatchFinding>()

        val missingLocationPermission = requestedPermissions.none { it in locationPermissions }
        val locationHosts = allHosts.filter(::isLocationHost)
        if (missingLocationPermission && locationHosts.isNotEmpty()) {
            findings += PermissionMismatchFinding(
                id = "location_without_permission",
                severity = MismatchSeverity.HIGH,
                title = "Location-style traffic without location permission",
                summary = "$appName is contacting mapping or geolocation infrastructure even though it does not declare Android location permission.",
                evidence = buildList {
                    add("Observed hosts: ${locationHosts.take(3).joinToString()}")
                    add("Declared location permissions: none")
                },
            )
        }

        val missingContactsPermission = requestedPermissions.none { it in contactsPermissions }
        val socialHosts = allHosts.filter(::isSocialIdentityHost)
        if (missingContactsPermission && socialHosts.isNotEmpty()) {
            findings += PermissionMismatchFinding(
                id = "social_identity_without_contacts",
                severity = MismatchSeverity.MEDIUM,
                title = "Identity or social API traffic without contacts permission",
                summary = "$appName reached social or identity endpoints that often back login, graph, or contact-sync flows, but the app does not request contacts-related permission.",
                evidence = buildList {
                    add("Observed hosts: ${socialHosts.take(3).joinToString()}")
                    add("Declared contacts permissions: none")
                },
            )
        }

        val isUtilityApp = looksLikeUtilityApp(appName, packageName)
        val observedTrackers = allHosts
            .mapNotNull(TrackerDatabase::lookupByDomain)
            .filter { it.category in trackerSensitiveCategories }
            .distinctBy { it.name }

        val utilityTrackerEvidence = (observedTrackers + detectedSdks.filter { it.category in trackerSensitiveCategories })
            .distinctBy { it.name }
        if (isUtilityApp && utilityTrackerEvidence.isNotEmpty()) {
            findings += PermissionMismatchFinding(
                id = "utility_tracker_stack",
                severity = if (utilityTrackerEvidence.size >= 2) MismatchSeverity.HIGH else MismatchSeverity.MEDIUM,
                title = "Utility-style app contacting tracker infrastructure",
                summary = "$appName looks like a utility app, but its network behavior includes ad, analytics, social, or profiling endpoints that usually do not match its core job.",
                evidence = buildList {
                    add("Tracker evidence: ${utilityTrackerEvidence.take(4).joinToString { it.name }}")
                    if (observedTrackers.isNotEmpty()) {
                        add("Tracker hosts: ${observedTrackers.take(3).joinToString { it.domains.firstOrNull().orEmpty() }}")
                    }
                },
            )
        }

        val totalBytesOut = profiles.sumOf { it.totalBytesOut }
        val totalConnections = profiles.sumOf { it.connectionCount }.coerceAtLeast(1L)
        val weightedBackgroundRatio = profiles.sumOf { (it.backgroundRatio * it.connectionCount.toFloat()).toDouble() } / totalConnections
        val nightEvents = profiles.sumOf { profile ->
            profile.hourlyDistribution.mapIndexed { hour, count ->
                if (hour in 0..5) count else 0
            }.sum()
        }
        val sensitivePermissionCount = requestedPermissions.count { it in dataSensitivePermissions }
        if (
            isUtilityApp &&
            totalBytesOut >= 2_000_000L &&
            weightedBackgroundRatio >= 0.75 &&
            nightEvents >= 3 &&
            sensitivePermissionCount <= 1
        ) {
            findings += PermissionMismatchFinding(
                id = "background_upload_mismatch",
                severity = MismatchSeverity.HIGH,
                title = "Background upload pattern exceeds declared capability",
                summary = "$appName uploaded ${formatBytes(totalBytesOut)} with a strong background/night pattern, despite exposing very few sensitive Android permissions.",
                evidence = buildList {
                    add("Background ratio: ${(weightedBackgroundRatio * 100).roundToInt()}%")
                    add("Night activity events: $nightEvents")
                    add("Sensitive permissions declared: $sensitivePermissionCount")
                },
            )
        }

        return findings
            .distinctBy { it.id }
            .sortedByDescending { it.severity.rank }
    }

    private fun looksLikeUtilityApp(appName: String, packageName: String): Boolean {
        val haystack = "${appName.lowercase()} ${packageName.lowercase()}"
        return utilityTokens.any { token -> token in haystack }
    }

    private fun isLocationHost(host: String): Boolean =
        locationHostKeywords.any { keyword -> keyword in host }

    private fun isSocialIdentityHost(host: String): Boolean =
        socialIdentityHosts.any { candidate -> host == candidate || host.endsWith(".$candidate") }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "${(bytes / (1024.0 * 1024.0 * 1024.0) * 10).roundToInt() / 10.0} GB"
        bytes >= 1024L * 1024L         -> "${(bytes / (1024.0 * 1024.0) * 10).roundToInt() / 10.0} MB"
        bytes >= 1024L                 -> "${(bytes / 1024.0 * 10).roundToInt() / 10.0} KB"
        else                           -> "$bytes B"
    }
}
