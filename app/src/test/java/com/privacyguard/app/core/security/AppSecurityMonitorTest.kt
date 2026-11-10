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

    // A stand-in certificate; its SHA-256 is computed, not hardcoded.
    private val cert = "CoreLock test certificate".toByteArray()
    private val certSha256Upper = java.security.MessageDigest.getInstance("SHA-256")
        .digest(cert).joinToString("") { "%02X".format(it) }

    @Test
    fun `release signature matches in any common format`() {
        // apksigner prints lowercase; keytool prints colon-separated uppercase. 1.0.0 only
        // accepted the uppercase form and reported a genuine install as tampered.
        val forms = listOf(
            certSha256Upper,
            certSha256Upper.lowercase(),
            certSha256Upper.chunked(2).joinToString(":"),
            " ${certSha256Upper.lowercase()} ",
        )
        for (form in forms) {
            assertEquals(form, true, AppSecurityMonitor.signatureMatches(form, listOf(cert), isDebugBuild = false))
        }
    }

    @Test
    fun `other certificate does not match`() {
        val other = "someone else's key".toByteArray()
        assertEquals(false, AppSecurityMonitor.signatureMatches(certSha256Upper, listOf(other), isDebugBuild = false))
        assertEquals(false, AppSecurityMonitor.signatureMatches(certSha256Upper.lowercase(), listOf(other), isDebugBuild = false))
    }

    @Test
    fun `blank expected hash passes only in debug builds`() {
        assertEquals(false, AppSecurityMonitor.signatureMatches("", listOf(cert), isDebugBuild = false))
        assertEquals(true, AppSecurityMonitor.signatureMatches("", listOf(cert), isDebugBuild = true))
        assertEquals(false, AppSecurityMonitor.signatureMatches(certSha256Upper, emptyList(), isDebugBuild = false))
    }
}
