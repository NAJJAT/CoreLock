package com.privacyguard.app.core.detection

import com.privacyguard.core.metadata.ConnectionProfile

data class StalkerwareAssessment(
    val score: Int,
    val reasons: List<String>,
)

/**
 * Scores the signals that distinguish monitoring apps (hidden icon, sideloaded,
 * background location, covert uploads at night) rather than traits most ordinary
 * apps share. Earlier weights (any location permission, "not from Play", "mostly
 * background", "unknown IP owner" from a tiny GeoIP table) scored messengers,
 * banking apps and the preinstalled browser at 45-60.
 */
object StalkerwareDetector {
    private const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
    private val foregroundLocation = setOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
    )
    // Official stores; anything else (or no installer) means sideloaded.
    private val trustedInstallers = setOf(
        "com.android.vending",            // Google Play
        "com.sec.android.app.samsungapps", // Galaxy Store
        "com.huawei.appmarket",
        "com.amazon.venezia",
        "com.xiaomi.mipicks",
        "com.heytap.market", "com.oppo.market",
    )

    fun assess(
        requestedPermissions: Set<String>,
        profiles: List<ConnectionProfile>,
        hasLauncherIcon: Boolean,
        installerPackage: String?,
        isSystemApp: Boolean = false,
    ): StalkerwareAssessment {
        // Preinstalled system apps are out of scope: they cannot be secretly installed.
        if (isSystemApp) return StalkerwareAssessment(0, emptyList())

        var score = 0
        val reasons = mutableListOf<String>()

        if (!hasLauncherIcon) {
            score += 30
            reasons += "No launcher icon"
        }
        if (installerPackage.isNullOrBlank() || installerPackage !in trustedInstallers) {
            score += 20
            reasons += "Not installed from an app store"
        }
        if (BACKGROUND_LOCATION in requestedPermissions) {
            score += 20
            reasons += "Requests background location"
        } else if (requestedPermissions.any { it in foregroundLocation }) {
            score += 5
        }

        if (profiles.any { it.backgroundRatio >= 0.8f }) {
            score += 10
            reasons += "Mostly background traffic"
        }

        val nightHeavy = profiles.any { profile ->
            val total = profile.hourlyDistribution.sum()
            total >= MIN_NIGHT_SAMPLE &&
                profile.hourlyDistribution.slice(0..5).sum().toFloat() / total >= 0.5f
        }
        if (nightHeavy) {
            score += 15
            reasons += "High night-activity ratio"
        }

        if (profiles.any { it.totalBytesOut >= 1_000_000L && it.backgroundRatio >= 0.75f }) {
            score += 15
            reasons += "Background upload behavior"
        }

        return StalkerwareAssessment(
            score = score.coerceIn(0, 100),
            reasons = reasons.distinct().take(4),
        )
    }

    // A handful of connections in the small hours is not a pattern.
    private const val MIN_NIGHT_SAMPLE = 10
}
