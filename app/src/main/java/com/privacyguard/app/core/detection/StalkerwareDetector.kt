package com.privacyguard.app.core.detection

import com.privacyguard.app.core.geoip.GeoIpResolver
import com.privacyguard.core.metadata.ConnectionProfile

data class StalkerwareAssessment(
    val score: Int,
    val reasons: List<String>,
)

object StalkerwareDetector {
    private val locationPermissions = setOf(
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
    )

    fun assess(
        requestedPermissions: Set<String>,
        profiles: List<ConnectionProfile>,
        hasLauncherIcon: Boolean,
        installerPackage: String?,
    ): StalkerwareAssessment {
        var score = 0
        val reasons = mutableListOf<String>()

        if (!hasLauncherIcon) {
            score += 25
            reasons += "No launcher icon"
        }
        if (installerPackage.isNullOrBlank() || installerPackage != "com.android.vending") {
            score += 15
            reasons += "Not installed from Play Store"
        }
        if (requestedPermissions.any { it in locationPermissions }) {
            score += 15
            reasons += "Requests location permission"
        }

        val backgroundHeavy = profiles.any { it.backgroundRatio >= 0.8f }
        if (backgroundHeavy) {
            score += 20
            reasons += "Mostly background traffic"
        }

        val nightHeavy = profiles.any { profile ->
            val total = profile.hourlyDistribution.sum().coerceAtLeast(1)
            val night = profile.hourlyDistribution.slice(0..5).sum()
            night.toFloat() / total.toFloat() >= 0.5f
        }
        if (nightHeavy) {
            score += 15
            reasons += "High night-activity ratio"
        }

        val unknownOwnership = profiles.any { profile ->
            GeoIpResolver.lookup(profile.destinationIp)?.org == "Unknown"
        }
        if (unknownOwnership) {
            score += 10
            reasons += "Connects to unknown IP ownership"
        }

        val backgroundUpload = profiles.any { it.totalBytesOut >= 1_000_000L && it.backgroundRatio >= 0.75f }
        if (backgroundUpload) {
            score += 15
            reasons += "Background upload behavior"
        }

        return StalkerwareAssessment(
            score = score.coerceIn(0, 100),
            reasons = reasons.distinct().take(4),
        )
    }
}
