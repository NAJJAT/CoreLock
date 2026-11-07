package com.privacyguard.vpn.mitm

import com.privacyguard.vpn.mitm.CaUpgradeNotice.State.DONE
import com.privacyguard.vpn.mitm.CaUpgradeNotice.State.NONE
import com.privacyguard.vpn.mitm.CaUpgradeNotice.State.PENDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner's decision 3 (2026-11-05): after the update from 1.1, users who had HTTPS
 * inspection on see one Home card saying inspection needs the new certificate, with a
 * button to install it. Nobody else ever sees it, and it never comes back.
 */
class CaUpgradeNoticeTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private val legacy = CaPolicy.CaInfo(CaPolicy.LEGACY_CN, CaPolicy.LEGACY_ORG, now - 100 * day, now + 265 * day, 0)
    private val current = CaPolicy.CaInfo(CaPolicy.CN, CaPolicy.ORG, now - day, now + 96 * day, 0)

    @Test
    fun a_1_1_user_with_inspection_on_gets_the_notice() {
        assertEquals(PENDING, CaUpgradeNotice.atStart(NONE, inspectionOn = true, storedCa = legacy))
        // Also when the VPN replaced the old CA before the app's start check ran.
        assertEquals(PENDING, CaUpgradeNotice.onReplaced(NONE, inspectionOn = true, oldCa = legacy))
    }

    @Test
    fun nobody_else_gets_it() {
        // Inspection off (the 1.1 CA is deleted with it), a fresh 1.2 install, or a normal 90-day rotation.
        assertEquals(NONE, CaUpgradeNotice.atStart(NONE, inspectionOn = false, storedCa = legacy))
        assertEquals(NONE, CaUpgradeNotice.atStart(NONE, inspectionOn = true, storedCa = null))
        assertEquals(NONE, CaUpgradeNotice.atStart(NONE, inspectionOn = true, storedCa = current))
        assertEquals(NONE, CaUpgradeNotice.onReplaced(NONE, inspectionOn = true, oldCa = current))
        assertEquals(NONE, CaUpgradeNotice.onReplaced(NONE, inspectionOn = false, oldCa = legacy))
    }

    @Test
    fun it_is_shown_until_the_new_certificate_is_trusted_or_dismissed() {
        assertTrue(CaUpgradeNotice.show(PENDING, inspectionOn = true, newCaTrusted = false))
        assertFalse(CaUpgradeNotice.show(PENDING, inspectionOn = true, newCaTrusted = true))
        assertFalse(CaUpgradeNotice.show(PENDING, inspectionOn = false, newCaTrusted = false))
        assertFalse(CaUpgradeNotice.show(NONE, inspectionOn = true, newCaTrusted = false))
        assertFalse(CaUpgradeNotice.show(DONE, inspectionOn = true, newCaTrusted = false))
        // Trusted: finished for good.
        assertEquals(DONE, CaUpgradeNotice.afterCheck(PENDING, newCaTrusted = true))
        assertEquals(PENDING, CaUpgradeNotice.afterCheck(PENDING, newCaTrusted = false))
    }

    @Test
    fun it_is_one_time() {
        // Dismissed or finished: a later start, a later replacement, never brings it back.
        for (later in listOf(
            CaUpgradeNotice.atStart(DONE, inspectionOn = true, storedCa = legacy),
            CaUpgradeNotice.onReplaced(DONE, inspectionOn = true, oldCa = legacy),
            CaUpgradeNotice.afterCheck(DONE, newCaTrusted = false),
        )) assertEquals(DONE, later)
        assertEquals(PENDING, CaUpgradeNotice.atStart(PENDING, inspectionOn = true, storedCa = current))
    }

    @Test
    fun stored_values_round_trip_and_unknown_ones_read_as_none() {
        for (s in CaUpgradeNotice.State.values()) assertEquals(s, CaUpgradeNotice.State.parse(s.stored))
        assertEquals(NONE, CaUpgradeNotice.State.parse(null))
        assertEquals(NONE, CaUpgradeNotice.State.parse("garbage"))
    }
}
