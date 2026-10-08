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

/** One destination as seen in this app's traffic, for [PermissionMismatchDetector.exposure]. */
data class ObservedDestination(
    val host: String,
    val owner: String,
    /** Tracker product name when the host is a listed tracker (and not the app's own company). */
    val trackerName: String?,
    val firstParty: Boolean,
    val bytesSent: Long,
    val backgroundConnections: Int,
)

object PermissionMismatchDetector {

    /** Plain-language names for the sensitive permissions we check. */
    val SENSITIVE_PERMISSION_LABELS: Map<String, String> = linkedMapOf(
        "android.permission.ACCESS_FINE_LOCATION" to "Precise location",
        "android.permission.ACCESS_COARSE_LOCATION" to "Approximate location",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "Location in background",
        "android.permission.READ_CONTACTS" to "Contacts",
        "android.permission.GET_ACCOUNTS" to "Accounts on the phone",
        "android.permission.RECORD_AUDIO" to "Microphone",
        "android.permission.CAMERA" to "Camera",
        "android.permission.READ_CALENDAR" to "Calendar",
        "android.permission.READ_CALL_LOG" to "Call log",
        "android.permission.READ_SMS" to "SMS messages",
        "android.permission.READ_PHONE_STATE" to "Phone identity & state",
        "android.permission.READ_MEDIA_IMAGES" to "Photos",
        "android.permission.READ_MEDIA_VIDEO" to "Videos",
        "android.permission.READ_EXTERNAL_STORAGE" to "Files & media",
        "android.permission.BODY_SENSORS" to "Body sensors",
        "android.permission.ACTIVITY_RECOGNITION" to "Physical activity",
        "android.permission.BLUETOOTH_SCAN" to "Nearby devices",
        "android.permission.NEARBY_WIFI_DEVICES" to "Nearby Wi-Fi devices",
    )

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

    /**
     * What the app does with access it actually holds: sensitive permissions that
     * are granted, combined with third-party trackers, background uploads and
     * activity while the phone is idle. Unlike [analyze], which looks for traffic
     * the app should not be able to produce, this applies to apps that hold
     * plenty of permissions (WhatsApp, TikTok, Snapchat).
     */
    fun exposure(
        appName: String,
        grantedPermissions: Set<String>,
        destinations: List<ObservedDestination>,
        idleDnsQueries: Int,
    ): List<PermissionMismatchFinding> {
        val granted = SENSITIVE_PERMISSION_LABELS.filterKeys { it in grantedPermissions }.values.toList()
        if (granted.isEmpty()) return emptyList()
        val findings = mutableListOf<PermissionMismatchFinding>()
        val grantedList = granted.joinToString()

        val trackers = destinations.filter { it.trackerName != null && !it.firstParty }
        if (trackers.isNotEmpty()) {
            val holdsLocationOrContacts = grantedPermissions.any { it in locationPermissions || it in contactsPermissions }
            findings += PermissionMismatchFinding(
                id = "granted_access_with_trackers",
                severity = if (holdsLocationOrContacts) MismatchSeverity.HIGH else MismatchSeverity.MEDIUM,
                title = "Has access to $grantedList — and talks to trackers",
                summary = "$appName can read data behind these permissions and sends traffic to third-party tracking companies. " +
                    "The contents are encrypted, so what is sent cannot be confirmed, but the capability and the recipients are both present.",
                evidence = trackers.sortedByDescending { it.bytesSent }.take(4).map {
                    "${it.trackerName} (${it.owner}) · ${it.host} · ${formatBytes(it.bytesSent)} sent"
                },
            )
        }

        val backgroundUpload = destinations.filter { it.backgroundConnections > 0 }
        val backgroundBytes = backgroundUpload.sumOf { it.bytesSent }
        if (backgroundBytes >= 1_000_000L) {
            findings += PermissionMismatchFinding(
                id = "granted_access_background_upload",
                severity = MismatchSeverity.MEDIUM,
                title = "Uploads in the background while holding $grantedList",
                summary = "$appName sent ${formatBytes(backgroundBytes)} while you were not using it.",
                evidence = backgroundUpload.sortedByDescending { it.bytesSent }.take(4).map {
                    "${it.host} (${it.owner}) · ${it.backgroundConnections} background connections · ${formatBytes(it.bytesSent)} sent"
                },
            )
        }

        if (idleDnsQueries >= 20) {
            findings += PermissionMismatchFinding(
                id = "granted_access_idle_activity",
                severity = MismatchSeverity.LOW,
                title = "Active while the phone was idle",
                summary = "$appName made $idleDnsQueries DNS lookups while the screen was off. " +
                    "Messaging apps do this to receive messages; other apps rarely need to.",
                evidence = listOf("Holds: $grantedList"),
            )
        }
        return findings
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
