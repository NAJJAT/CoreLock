package com.privacyguard.vpn.mitm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan task 2.3, ADR 0009: the CA's name, its 90-day rotation, and one prompt with consent. */
class CaPolicyTest {

    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L          // a fixed "now" (2027)

    private fun ca(createdAt: Long, days: Int = CaPolicy.VALIDITY_DAYS, cn: String = CaPolicy.CN, org: String = CaPolicy.ORG, pathLen: Int = 0) =
        CaPolicy.CaInfo(cn = cn, org = org, notBefore = createdAt - day, notAfter = createdAt + days * day, pathLen = pathLen)

    private fun replace(ca: CaPolicy.CaInfo, now: Long, consentAt: Long = 0L, byOrg: Boolean = false) =
        CaPolicy.mustReplace(ca, now, consentAt, byOrg)

    // ── Name ──────────────────────────────────────────────────────────────────

    @Test
    fun the_ca_is_called_corelock() {
        assertTrue(CaPolicy.CN.startsWith("CoreLock"))
        assertFalse("PrivacyGuard" in CaPolicy.CN + CaPolicy.ORG)
    }

    @Test
    fun a_ca_with_the_old_name_is_replaced_once() {
        val old = ca(t0, cn = "PrivacyGuard Enterprise CA", org = "PrivacyGuard")
        assertTrue(replace(old, t0 + day))
        assertFalse(replace(ca(t0), t0 + day))
    }

    @Test
    fun old_and_new_cas_are_recognised_as_ours_and_others_are_not() {
        assertTrue(CaPolicy.isOurs(CaPolicy.CN, CaPolicy.ORG))
        assertTrue(CaPolicy.isOurs("PrivacyGuard Enterprise CA", "PrivacyGuard"))
        assertFalse(CaPolicy.isOurs("PrivacyGuard Enterprise CA", "Someone Else"))
        assertFalse(CaPolicy.isOurs("Corporate Root CA", "CoreLock"))
        assertFalse(CaPolicy.isOurs(null, null))
    }

    // ── Lifetime ──────────────────────────────────────────────────────────────

    @Test
    fun a_new_ca_covers_the_90_day_consent_and_no_more_than_100_days() {
        assertTrue(CaPolicy.VALIDITY_DAYS in (CaPolicy.CONSENT_DAYS + 1)..CaPolicy.MAX_LIFETIME_DAYS)
        assertEquals(90, CaPolicy.CONSENT_DAYS)
    }

    @Test
    fun the_one_year_ca_of_1_1_is_replaced() {
        assertTrue(replace(ca(t0, days = 365), t0 + day))
        assertTrue(replace(ca(t0, days = 3650), t0 + day))
    }

    @Test
    fun an_expired_ca_or_one_that_may_sign_other_cas_is_replaced() {
        assertTrue(replace(ca(t0), t0 + CaPolicy.VALIDITY_DAYS * day))
        assertTrue(replace(ca(t0, pathLen = 1), t0 + day))
        assertTrue(replace(ca(t0, pathLen = -1), t0 + day))       // not a CA at all
    }

    // ── Rotation with the user's consent (one prompt) ────────────────────────

    @Test
    fun during_the_consent_period_the_ca_is_kept() {
        val consentAt = t0
        val created = t0
        for (d in listOf(1, 30, 60, 89, 92)) assertFalse("day $d", replace(ca(created), t0 + d * day, consentAt))
    }

    @Test
    fun renewing_consent_rotates_the_ca_at_once() {
        val created = t0
        val renewedAt = t0 + 90 * day
        assertTrue(replace(ca(created), renewedAt + 1_000, consentAt = renewedAt))
        // The new CA then lasts through the new consent period without another prompt.
        val next = ca(renewedAt + 1_000)
        for (d in listOf(1, 45, 89)) assertFalse("day $d after renewal", replace(next, renewedAt + d * day, renewedAt))
    }

    @Test
    fun a_ca_made_shortly_before_consent_is_kept_and_an_older_one_is_not() {
        // The screen creates the CA when it opens; consent may come a little later.
        assertFalse(replace(ca(t0), t0 + 5 * day, consentAt = t0 + 5 * day))
        assertTrue(replace(ca(t0), t0 + 10 * day, consentAt = t0 + 10 * day))
    }

    @Test
    fun a_consent_time_in_the_future_cannot_make_the_ca_rotate_forever() {
        val now = t0
        val fresh = ca(now)
        assertFalse(replace(fresh, now, consentAt = now + 400 * day))
    }

    // ── Organisation consent (MDM): it does not expire ───────────────────────

    @Test
    fun with_mdm_consent_the_ca_rotates_two_weeks_before_it_ends() {
        val created = t0
        val end = created + CaPolicy.VALIDITY_DAYS * day
        assertFalse(replace(ca(created), end - 15 * day, consentAt = t0, byOrg = true))
        assertTrue(replace(ca(created), end - 13 * day, consentAt = t0, byOrg = true))
    }

    @Test
    fun without_consent_only_the_basic_rules_apply() {
        assertFalse(replace(ca(t0), t0 + 80 * day))
        assertTrue(replace(ca(t0), t0 + CaPolicy.VALIDITY_DAYS * day + 1))
    }
}
