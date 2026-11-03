package com.privacyguard.app.vpn

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UnexpectedStopTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        UnexpectedStop.clear(context)
        forgetLoaded()
    }

    /** As after an app restart: the next read comes from the stored file. */
    private fun forgetLoaded() {
        UnexpectedStop::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(UnexpectedStop, false)
    }

    @Test
    fun a_recorded_reason_survives_a_restart_and_the_next_start_clears_it() {
        assertNull(UnexpectedStop.reason(context).value)
        UnexpectedStop.record(context, UnexpectedStop.Reason.OTHER_VPN)
        assertEquals(UnexpectedStop.Reason.OTHER_VPN, UnexpectedStop.reason(context).value)

        forgetLoaded()
        assertEquals(UnexpectedStop.Reason.OTHER_VPN, UnexpectedStop.reason(context).value)

        UnexpectedStop.clear(context)
        forgetLoaded()
        assertNull(UnexpectedStop.reason(context).value)
    }

    @Test
    fun an_unknown_stored_value_reads_as_no_reason() {
        context.getSharedPreferences("privacyguard_unexpected_stop", Context.MODE_PRIVATE)
            .edit().putString("reason", "FROM_A_NEWER_VERSION").commit()
        forgetLoaded()
        assertNull(UnexpectedStop.reason(context).value)
    }

    @Test
    fun only_a_revoke_that_hands_the_vpn_to_another_app_is_unexpected() {
        assertEquals(UnexpectedStop.Reason.OTHER_VPN, UnexpectedStop.forRevoke(consentStillOurs = false))
        // Disconnected in Android's VPN settings, or Always-on turned off: the user's choice.
        assertNull(UnexpectedStop.forRevoke(consentStillOurs = true))
    }

    private val service = File("src/main/java/com/privacyguard/platform/android/PrivacyVpnService.kt").readText()

    private fun block(start: String, end: String): String {
        val from = service.indexOf(start)
        assertTrue("not found: $start", from >= 0)
        val to = service.indexOf(end, from)
        assertTrue("not found after $start: $end", to > from)
        return service.substring(from, to)
    }

    private fun assertBefore(text: String, first: String, then: String) {
        val a = text.indexOf(first)
        val b = text.indexOf(then)
        assertTrue("missing '$first'", a >= 0)
        assertTrue("missing '$then'", b >= 0)
        assertTrue("'$first' must come before '$then'", a < b)
    }

    /**
     * Home reads the reason when the service publishes STOPPED (in releaseResourcesLocked,
     * inside stopVpn). Recorded after the stop, Home would show amber "off" first.
     */
    @Test
    fun the_service_records_the_reason_before_it_stops() {
        assertBefore(block("override fun onRevoke()", "super.onRevoke()"), "UnexpectedStop.forRevoke", "stopVpn()")
        assertBefore(block("Packet path keeps failing", "return@launch"), "UnexpectedStop.Reason.PACKET_PATH_FAILED", "stopVpn()")
        assertBefore(block("if (fresh == null) {", "false"), "UnexpectedStop.Reason.PACKET_PATH_FAILED", "releaseResourcesLocked()")
        assertBefore(block("private fun startVpn()", "startJob = scope.launch"), "UnexpectedStop.clear(this)", "State.STARTING")
    }

    /** A record only: it must never start, stop or restart protection itself. */
    @Test
    fun the_record_cannot_change_what_the_service_does() {
        val recorder = File("src/main/java/com/privacyguard/app/vpn/UnexpectedStop.kt").readText()
            .lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/") }.joinToString("\n")
        for (call in listOf("startService", "startForegroundService", "stopService", "VpnManager", "PrivacyVpnService", "ProtectionWatchdog", "stopVpn", "startVpn")) {
            assertFalse("UnexpectedStop must not use $call", recorder.contains(call))
        }
    }
}
