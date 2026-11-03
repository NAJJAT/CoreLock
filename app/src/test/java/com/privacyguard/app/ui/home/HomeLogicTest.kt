package com.privacyguard.app.ui.home

import com.privacyguard.app.R
import com.privacyguard.app.core.power.BatteryCheck
import com.privacyguard.app.ui.components.ProtectionState
import com.privacyguard.app.ui.dashboard.StrictSuggestion
import com.privacyguard.app.vpn.UnexpectedStop
import com.privacyguard.platform.android.PrivacyVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HomeLogicTest {

    @Test
    fun hero_state_comes_from_the_service_and_the_last_stop() {
        assertEquals(ProtectionState.ON, HomeLogic.protectionState(PrivacyVpnService.State.RUNNING, shouldBeActive = true))
        assertEquals(ProtectionState.STARTING, HomeLogic.protectionState(PrivacyVpnService.State.STARTING, shouldBeActive = false))
        // The user stopped it: off (amber), never "stopped unexpectedly" (red).
        assertEquals(ProtectionState.OFF, HomeLogic.protectionState(PrivacyVpnService.State.STOPPED, shouldBeActive = false))
        // Should be on, but nothing runs: crashed or killed.
        assertEquals(ProtectionState.STOPPED_UNEXPECTEDLY, HomeLogic.protectionState(PrivacyVpnService.State.STOPPED, shouldBeActive = true))
    }

    @Test
    fun a_recorded_reason_makes_a_stop_unexpected_and_says_why() {
        // stopVpn cleared "should be on", but the reason says nobody asked for this stop.
        for (reason in UnexpectedStop.Reason.entries) {
            assertEquals(ProtectionState.STOPPED_UNEXPECTEDLY, HomeLogic.protectionState(PrivacyVpnService.State.STOPPED, false, reason))
        }
        // Running or starting again: the old reason no longer describes anything.
        assertEquals(ProtectionState.ON, HomeLogic.protectionState(PrivacyVpnService.State.RUNNING, false, UnexpectedStop.Reason.OTHER_VPN))
        assertEquals(ProtectionState.STARTING, HomeLogic.protectionState(PrivacyVpnService.State.STARTING, false, UnexpectedStop.Reason.PACKET_PATH_FAILED))
        val stopped = ProtectionState.STOPPED_UNEXPECTEDLY
        assertEquals(R.string.ui_home_stopped_other_vpn, HomeLogic.heroSubtitle(stopped, "STANDARD", UnexpectedStop.Reason.OTHER_VPN))
        assertEquals(R.string.ui_home_stopped_failed, HomeLogic.heroSubtitle(stopped, "STANDARD", UnexpectedStop.Reason.PACKET_PATH_FAILED))
        assertEquals(R.string.ui_home_stopped_sub, HomeLogic.heroSubtitle(stopped, "STANDARD", null))
    }

    @Test
    fun today_starts_at_local_midnight_also_on_a_dst_day() {
        val zoneId = ZoneId.of("Europe/Amsterdam")
            val zone = java.util.TimeZone.getTimeZone(zoneId)
        // 2026-10-25 is the end of summer time there: that day has 25 hours.
        val at = LocalDateTime.of(2026, 10, 25, 23, 30).atZone(zoneId).toInstant().toEpochMilli()
        val midnight = LocalDateTime.of(2026, 10, 25, 0, 0).atZone(zoneId).toInstant().toEpochMilli()
        val next = LocalDateTime.of(2026, 10, 26, 0, 0).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(midnight, HomeLogic.startOfDay(at, zone))
        assertEquals(next, HomeLogic.nextMidnight(at, zone))
        assertEquals(25L * 60 * 60_000, next - midnight)
        assertEquals("midnight itself belongs to the new day", next, HomeLogic.startOfDay(next, zone))
    }

    @Test
    fun a_day_without_a_midnight_starts_at_its_first_real_minute() {
        // Chile moves its clocks forward at midnight: 2026-09-06 00:00 does not exist there.
        val zoneId = ZoneId.of("America/Santiago")
        val zone = java.util.TimeZone.getTimeZone(zoneId)
        val day = java.time.LocalDate.of(2026, 9, 6)
        val expected = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val noon = day.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
        assertEquals(expected, HomeLogic.startOfDay(noon, zone))
        assertEquals(expected, HomeLogic.nextMidnight(noon - 24L * 60 * 60_000, zone))
    }

    private val strict =StrictSuggestion("wifi:home", "Home Wi-Fi", 40)
    private val alert = HomeLogic.HighAlert("Malware lookup", 1L)
    private val everything = HomeLogic.AttentionInputs(
        privateDnsHost = "dns.example",
        usageAccessMissingForRule = true,
        battery = BatteryCheck.State.RESTRICTED,
        newHighAlert = alert,
        whatsNew = true,
        strictSuggestion = strict,
    )

    @Test
    fun only_the_first_problem_that_applies_is_shown_in_spec_order() {
        var inputs = everything
        val seen = mutableListOf<HomeLogic.Attention?>()
        repeat(7) {
            val a = HomeLogic.attention(inputs)
            seen += a
            inputs = when (a) {
                is HomeLogic.Attention.PrivateDns -> inputs.copy(privateDnsHost = null)
                HomeLogic.Attention.UsageAccess -> inputs.copy(usageAccessMissingForRule = false)
                is HomeLogic.Attention.Battery -> inputs.copy(battery = BatteryCheck.State.UNRESTRICTED)
                is HomeLogic.Attention.NewAlert -> inputs.copy(newHighAlert = null)
                HomeLogic.Attention.WhatsNew -> inputs.copy(whatsNew = false)
                is HomeLogic.Attention.Strict -> inputs.copy(strictSuggestion = null)
                null -> inputs
            }
        }
        assertEquals(
            listOf(
                HomeLogic.Attention.PrivateDns("dns.example"),
                HomeLogic.Attention.UsageAccess,
                HomeLogic.Attention.Battery(canDismiss = false),
                HomeLogic.Attention.NewAlert(alert),
                HomeLogic.Attention.WhatsNew,
                HomeLogic.Attention.Strict(strict),
                null,
            ),
            seen,
        )
    }

    @Test
    fun battery_optimized_is_optional_and_stays_dismissed_but_restricted_is_not() {
        val optimized = HomeLogic.AttentionInputs(battery = BatteryCheck.State.OPTIMIZED)
        assertEquals(HomeLogic.Attention.Battery(canDismiss = true), HomeLogic.attention(optimized))
        assertNull(HomeLogic.attention(optimized.copy(batteryDismissed = true)))
        val restricted = HomeLogic.AttentionInputs(battery = BatteryCheck.State.RESTRICTED, batteryDismissed = true)
        assertEquals(HomeLogic.Attention.Battery(canDismiss = false), HomeLogic.attention(restricted))
    }

    @Test
    fun hero_subtitle_names_the_level_only_when_protected() {
        assertEquals(R.string.ui_hero_sub_strict, HomeLogic.heroSubtitle(ProtectionState.ON, "STRICT"))
        assertEquals(R.string.ui_hero_sub_minimal, HomeLogic.heroSubtitle(ProtectionState.ON, "MINIMAL"))
        assertEquals(R.string.ui_hero_sub_standard, HomeLogic.heroSubtitle(ProtectionState.ON, "STANDARD"))
        // An unknown stored value shows the default level, never crashes.
        assertEquals(R.string.ui_hero_sub_standard, HomeLogic.heroSubtitle(ProtectionState.ON, "???"))
        assertEquals(R.string.ui_hero_off_sub, HomeLogic.heroSubtitle(ProtectionState.OFF, "STRICT"))
        assertEquals(R.string.ui_home_stopped_sub, HomeLogic.heroSubtitle(ProtectionState.STOPPED_UNEXPECTEDLY, "STRICT"))
    }
}
