package com.privacyguard.app.core.security

enum class SensitiveActionPolicy {
    ALLOW,
    REQUIRE_DEVICE_AUTH,
    BLOCK,
}

data class SensitiveActionDecision(
    val policy: SensitiveActionPolicy,
    val message: String,
)

object SecurityActionPolicy {
    fun decide(posture: SecurityPosture, deviceSecure: Boolean): SensitiveActionDecision {
        if (!posture.signatureValid) {
            return SensitiveActionDecision(
                policy = SensitiveActionPolicy.BLOCK,
                message = "Sensitive actions are disabled because app integrity could not be verified",
            )
        }

        if (posture.riskLevel == SecurityRiskLevel.HIGH && !deviceSecure) {
            return SensitiveActionDecision(
                policy = SensitiveActionPolicy.BLOCK,
                message = "Set a screen lock before using sensitive actions on a high-risk runtime",
            )
        }

        if (deviceSecure) {
            return SensitiveActionDecision(
                policy = SensitiveActionPolicy.REQUIRE_DEVICE_AUTH,
                message = "Device authentication required",
            )
        }

        return SensitiveActionDecision(
            policy = SensitiveActionPolicy.ALLOW,
            message = "Protected action allowed on this device",
        )
    }
}
