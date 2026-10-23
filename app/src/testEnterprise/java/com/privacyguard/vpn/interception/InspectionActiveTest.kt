package com.privacyguard.vpn.interception

import com.privacyguard.vpn.mitm.MitmConfig
import org.junit.Assert.assertEquals
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

    /** The indicator says "on" exactly when decrypting, and who turned it on. */
    @Test
    fun status_follows_the_decrypting_test_and_names_the_organisation() {
        assertEquals(InspectionStatus.ON, TlsInterceptionFactory.statusOf(enabled = true, consentValid = true, byOrganisation = false))
        assertEquals(InspectionStatus.ON_BY_ORGANISATION, TlsInterceptionFactory.statusOf(enabled = true, consentValid = true, byOrganisation = true))
        // Switched on without consent decrypts nothing, so it must not claim "on".
        assertEquals(InspectionStatus.OFF, TlsInterceptionFactory.statusOf(enabled = true, consentValid = false, byOrganisation = true))
        assertEquals(InspectionStatus.OFF, TlsInterceptionFactory.statusOf(enabled = false, consentValid = true, byOrganisation = false))
    }

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test
    fun user_consent_expires_after_90_days() {
        assertTrue(MitmConfig.consentValid(now - 89 * day, byOrganisation = false, now = now))
        assertFalse(MitmConfig.consentValid(now - 91 * day, byOrganisation = false, now = now))
    }

    /** Review finding #5: the organisation's consent expired mid-run after 90 days. */
    @Test
    fun organisation_consent_lasts_while_the_policy_asserts_it() {
        assertTrue(MitmConfig.consentValid(now - 400 * day, byOrganisation = true, now = now))
    }

    @Test
    fun no_consent_is_never_valid() {
        assertFalse(MitmConfig.consentValid(0L, byOrganisation = true, now = now))
        assertFalse(MitmConfig.consentValid(0L, byOrganisation = false, now = now))
    }
}
