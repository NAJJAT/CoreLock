package com.privacyguard.app.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSecurityMonitorTest {

    @Test
    fun `invalid signature is high risk`() {
        val level = AppSecurityMonitor.assessRiskLevel(
            signatureValid = false,
            debuggerAttached = false,
            rooted = false,
            suspiciousPackagesPresent = false,
            buildTagsRisk = false,
            hardwareBackedKeystore = true,
            emulatorDetected = false,
        )

        assertEquals(SecurityRiskLevel.HIGH, level)
    }

    @Test
    fun `debugger is high risk`() {
        val level = AppSecurityMonitor.assessRiskLevel(
            signatureValid = true,
            debuggerAttached = true,
            rooted = false,
            suspiciousPackagesPresent = false,
            buildTagsRisk = false,
            hardwareBackedKeystore = true,
            emulatorDetected = false,
        )

        assertEquals(SecurityRiskLevel.HIGH, level)
    }

    @Test
    fun `emulator is elevated risk`() {
        val level = AppSecurityMonitor.assessRiskLevel(
            signatureValid = true,
            debuggerAttached = false,
            rooted = false,
            suspiciousPackagesPresent = false,
            buildTagsRisk = false,
            hardwareBackedKeystore = true,
            emulatorDetected = true,
        )

        assertEquals(SecurityRiskLevel.ELEVATED, level)
    }

    @Test
    fun `hardware backed clean runtime is low risk`() {
        val level = AppSecurityMonitor.assessRiskLevel(
            signatureValid = true,
            debuggerAttached = false,
            rooted = false,
            suspiciousPackagesPresent = false,
            buildTagsRisk = false,
            hardwareBackedKeystore = true,
            emulatorDetected = false,
        )

        assertEquals(SecurityRiskLevel.LOW, level)
    }
}
