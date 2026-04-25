package com.privacyguard.app.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityActionPolicyTest {

    @Test
    fun `blocks sensitive actions when signature is invalid`() {
        val decision = SecurityActionPolicy.decide(
            posture = SecurityPosture(signatureValid = false, riskLevel = SecurityRiskLevel.HIGH),
            deviceSecure = true,
        )

        assertEquals(SensitiveActionPolicy.BLOCK, decision.policy)
    }

    @Test
    fun `requires device auth on secure low risk device`() {
        val decision = SecurityActionPolicy.decide(
            posture = SecurityPosture(signatureValid = true, riskLevel = SecurityRiskLevel.LOW),
            deviceSecure = true,
        )

        assertEquals(SensitiveActionPolicy.REQUIRE_DEVICE_AUTH, decision.policy)
    }

    @Test
    fun `allows protected action on unsecured low risk device`() {
        val decision = SecurityActionPolicy.decide(
            posture = SecurityPosture(signatureValid = true, riskLevel = SecurityRiskLevel.LOW),
            deviceSecure = false,
        )

        assertEquals(SensitiveActionPolicy.ALLOW, decision.policy)
    }

    @Test
    fun `blocks high risk runtime without device lock`() {
        val decision = SecurityActionPolicy.decide(
            posture = SecurityPosture(signatureValid = true, riskLevel = SecurityRiskLevel.HIGH),
            deviceSecure = false,
        )

        assertEquals(SensitiveActionPolicy.BLOCK, decision.policy)
    }
}
