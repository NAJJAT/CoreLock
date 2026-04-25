package com.privacyguard.app.core.behavior

import com.privacyguard.core.metadata.ConnectionProfile
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class BehaviorSeverity(val label: String, val rank: Int) {
    LOW("LOW", 0),
    MEDIUM("MED", 1),
    HIGH("HIGH", 2),
}

data class BehaviorFinding(
    val packageName: String,
    val hostname: String,
    val severity: BehaviorSeverity,
    val title: String,
    val summary: String,
    val evidence: List<String> = emptyList(),
)

data class AppBehaviorSummary(
    val packageName: String,
    val learningProgress: Float,
    val baselineReady: Boolean,
    val findings: List<BehaviorFinding>,
)

object BehaviorDnaAnalyzer {
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val LEARNING_WINDOW_MS = 7L * DAY_MS
    private const val NEW_DESTINATION_WINDOW_MS = DAY_MS
    private const val UPLOAD_SPIKE_MIN_BYTES = 2_000_000L

    fun summarizeAll(profiles: List<ConnectionProfile>, now: Long = System.currentTimeMillis()): List<AppBehaviorSummary> =
        profiles
            .groupBy { it.packageName }
            .map { (packageName, appProfiles) -> summarizeApp(packageName, appProfiles, now) }
            .sortedByDescending { summary ->
                summary.findings.maxOfOrNull { it.severity.rank } ?: -1
            }

    fun summarizeApp(
        packageName: String,
        profiles: List<ConnectionProfile>,
        now: Long = System.currentTimeMillis(),
    ): AppBehaviorSummary {
        if (profiles.isEmpty()) {
            return AppBehaviorSummary(
                packageName = packageName,
                learningProgress = 0f,
                baselineReady = false,
                findings = emptyList(),
            )
        }

        val earliestSeen = profiles.minOf { it.firstSeen }
        val latestSeen = profiles.maxOf { it.lastSeen }
        val spanMs = (latestSeen - earliestSeen).coerceAtLeast(0L)
        val totalConnections = profiles.sumOf { it.connectionCount }
        val learningProgress = maxOf(
            (spanMs.toDouble() / LEARNING_WINDOW_MS.toDouble()).toFloat(),
            (totalConnections.toFloat() / 40f).coerceIn(0f, 1f),
        ).coerceIn(0f, 1f)
        val baselineReady = spanMs >= LEARNING_WINDOW_MS || totalConnections >= 40

        val packageHours = IntArray(24)
        profiles.forEach { profile ->
            profile.hourlyDistribution.forEachIndexed { index, count ->
                packageHours[index] += count
            }
        }
        val totalHourlyEvents = packageHours.sum().coerceAtLeast(1)
        val averageUpload = profiles.map { it.totalBytesOut }.average().takeIf { it.isFinite() } ?: 0.0
        val recentProfiles = profiles.filter { now - it.lastSeen <= NEW_DESTINATION_WINDOW_MS }
        val olderProfiles = profiles.filter { now - it.firstSeen > 3L * DAY_MS }

        val findings = mutableListOf<BehaviorFinding>()

        if (baselineReady && olderProfiles.isNotEmpty()) {
            recentProfiles
                .filter { now - it.firstSeen <= NEW_DESTINATION_WINDOW_MS && it.connectionCount >= 3 }
                .sortedByDescending { it.connectionCount }
                .take(4)
                .forEach { profile ->
                    findings += BehaviorFinding(
                        packageName = packageName,
                        hostname = profile.hostname,
                        severity = BehaviorSeverity.MEDIUM,
                        title = "New destination after behavior baseline",
                        summary = "${profile.hostname} was first seen recently after $packageName already built a stable traffic pattern.",
                        evidence = listOf(
                            "First seen: ${((now - profile.firstSeen) / 3_600_000L).coerceAtLeast(0)}h ago",
                            "Connections: ${profile.connectionCount}",
                        ),
                    )
                }
        }

        profiles
            .filter { it.connectionCount >= 3 && now - it.lastSeen <= NEW_DESTINATION_WINDOW_MS }
            .forEach { profile ->
                val dominantHour = profile.hourlyDistribution.indices.maxByOrNull { profile.hourlyDistribution[it] } ?: 0
                val packageHourRatio = packageHours[dominantHour].toFloat() / totalHourlyEvents.toFloat()
                if (baselineReady && packageHourRatio <= 0.08f && profile.backgroundRatio >= 0.5f) {
                    findings += BehaviorFinding(
                        packageName = packageName,
                        hostname = profile.hostname,
                        severity = BehaviorSeverity.MEDIUM,
                        title = "Unusual active hour",
                        summary = "${profile.hostname} is most active around ${hourLabel(dominantHour)}, outside this app's usual rhythm.",
                        evidence = listOf(
                            "Background ratio: ${(profile.backgroundRatio * 100).roundToInt()}%",
                            "Package hour share: ${(packageHourRatio * 100).roundToInt()}%",
                        ),
                    )
                }
            }

        profiles
            .filter { it.totalBytesOut >= UPLOAD_SPIKE_MIN_BYTES && now - it.lastSeen <= NEW_DESTINATION_WINDOW_MS }
            .forEach { profile ->
                val referenceAverage = profiles
                    .filter { it.hostname != profile.hostname && it.totalBytesOut > 0L }
                    .map { it.totalBytesOut }
                    .average()
                    .takeIf { it.isFinite() && it > 0.0 }
                    ?: averageUpload
                val exceedsPackageAverage = referenceAverage <= 0.0 || profile.totalBytesOut >= referenceAverage * 3.0
                if (exceedsPackageAverage) {
                    findings += BehaviorFinding(
                        packageName = packageName,
                        hostname = profile.hostname,
                        severity = if (profile.backgroundRatio >= 0.75f) BehaviorSeverity.HIGH else BehaviorSeverity.MEDIUM,
                        title = "Upload spike",
                        summary = "${profile.hostname} uploaded much more data than this app normally sends.",
                        evidence = listOf(
                            "Bytes out: ${formatBytes(profile.totalBytesOut)}",
                            "Avg per destination: ${formatBytes(referenceAverage.roundToLongSafe())}",
                            "Background ratio: ${(profile.backgroundRatio * 100).roundToInt()}%",
                        ),
                    )
                }
            }

        profiles
            .filter { it.backgroundRatio >= 0.85f && it.connectionCount >= 8 && it.avgIntervalMs in 10_000L..15L * 60L * 1000L }
            .sortedByDescending { it.connectionCount }
            .take(3)
            .forEach { profile ->
                findings += BehaviorFinding(
                    packageName = packageName,
                    hostname = profile.hostname,
                    severity = BehaviorSeverity.HIGH,
                    title = "Background beacon pattern",
                    summary = "${profile.hostname} is contacted on a regular cadence mostly while the app is in the background.",
                    evidence = listOf(
                        "Avg interval: ${profile.avgIntervalMs / 1000}s",
                        "Connections: ${profile.connectionCount}",
                        "Background ratio: ${(profile.backgroundRatio * 100).roundToInt()}%",
                    ),
                )
            }

        return AppBehaviorSummary(
            packageName = packageName,
            learningProgress = learningProgress,
            baselineReady = baselineReady,
            findings = findings
                .distinctBy { "${it.title}|${it.hostname}" }
                .sortedByDescending { it.severity.rank }
                .take(6),
        )
    }

    private fun hourLabel(hour: Int): String = String.format(java.util.Locale.US, "%02d:00", hour)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "${((bytes / (1024.0 * 1024.0 * 1024.0)) * 10).roundToInt() / 10.0} GB"
        bytes >= 1024L * 1024L -> "${((bytes / (1024.0 * 1024.0)) * 10).roundToInt() / 10.0} MB"
        bytes >= 1024L -> "${((bytes / 1024.0) * 10).roundToInt() / 10.0} KB"
        else -> "$bytes B"
    }

    private fun Double.roundToLongSafe(): Long = if (isFinite()) roundToLong() else 0L
}
