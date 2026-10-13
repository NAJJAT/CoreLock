package com.privacyguard.vpn.interception

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #5 (enterprise): decrypting needs inspection switched on AND consent still valid. */
class InspectionActiveTest {
    @Test
    fun both_needed() {
        assertTrue(TlsInterceptionFactory.isInspectionActive(enabled = true, consentValid = true))
        assertFalse(TlsInterceptionFactory.isInspectionActive(enabled = true, consentValid = false))
        assertFalse(TlsInterceptionFactory.isInspectionActive(enabled = false, consentValid = true))
        assertFalse(TlsInterceptionFactory.isInspectionActive(enabled = false, consentValid = false))
    }
}
