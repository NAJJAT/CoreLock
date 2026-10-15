package com.privacyguard.app.vpn

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.privacyguard.platform.android.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowVpnService

/**
 * V-18: when the process dies with protection on, nothing inside it can say so;
 * the deadman alarm's receiver does. A deliberate stop must stay silent.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default installs Conscrypt JVM-wide, which broke the plain-JVM TLS
// tests (DohHostnameVerificationTest) that run later in the same fork.
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class ProtectionWatchdogTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() = BootReceiver.markVpnStopped(context)

    private fun stoppedNotification() =
        shadowOf(notifications).getNotification(NotificationHelper.NOTIFICATION_ID_PROTECTION_STOPPED)

    private fun nextStarted() = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService

    @Test
    fun acts_only_when_protection_should_run_and_does_not() {
        assertTrue(ProtectionWatchdog.protectionLost(shouldBeActive = true, running = false))
        assertFalse(ProtectionWatchdog.protectionLost(shouldBeActive = true, running = true))
        assertFalse(ProtectionWatchdog.protectionLost(shouldBeActive = false, running = false))
    }

    @Test
    fun alarm_after_a_crash_restarts_protection_silently() {
        ShadowVpnService.setPrepareResult(null)  // consent still granted
        BootReceiver.markVpnActive(context)       // a crash leaves this set

        ProtectionWatchdogReceiver().onReceive(context, Intent())

        assertEquals(com.privacyguard.platform.android.PrivacyVpnService::class.java.name,
            nextStarted()?.component?.className)
        assertNull(stoppedNotification())
    }

    @Test
    fun alarm_after_a_crash_without_consent_posts_protection_stopped() {
        ShadowVpnService.setPrepareResult(Intent("android.net.vpn.SETUP_VPN"))
        BootReceiver.markVpnActive(context)

        ProtectionWatchdogReceiver().onReceive(context, Intent())

        assertNull(nextStarted())
        val n = stoppedNotification()
        assertNotNull(n)
        n!!
        assertEquals(context.getString(com.privacyguard.app.R.string.notif_vpn_down_title),
            n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertNotNull("tapping must restart protection", n.contentIntent)
    }

    @Test
    fun a_start_that_keeps_crashing_stops_being_restarted() {
        ShadowVpnService.setPrepareResult(null)
        BootReceiver.markVpnActive(context)
        val t0 = 1_000_000L
        repeat(ProtectionWatchdog.MAX_RESTARTS) { i ->
            assertEquals(ProtectionWatchdog.Outcome.RESTARTED, ProtectionWatchdog.tryRestart(context, t0 + i * 60_000L))
        }
        assertEquals(ProtectionWatchdog.Outcome.TOO_MANY_RESTARTS,
            ProtectionWatchdog.tryRestart(context, t0 + ProtectionWatchdog.MAX_RESTARTS * 60_000L))
        // Once the window has passed, restarting is allowed again.
        assertEquals(ProtectionWatchdog.Outcome.RESTARTED,
            ProtectionWatchdog.tryRestart(context, t0 + ProtectionWatchdog.RESTART_WINDOW_MS + 1))
    }

    @Test
    fun alarm_after_a_deliberate_stop_stays_silent() {
        ProtectionWatchdogReceiver().onReceive(context, Intent())
        assertNull(stoppedNotification())
    }

    @Test
    fun an_app_update_restarts_protection_that_was_on() {
        ShadowVpnService.setPrepareResult(null)  // consent still granted
        BootReceiver.markVpnActive(context)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(com.privacyguard.platform.android.PrivacyVpnService::class.java.name, started?.component?.className)
    }

    @Test
    fun an_app_update_without_consent_records_that_protection_cannot_start() {
        ShadowVpnService.setPrepareResult(Intent("android.net.vpn.SETUP_VPN"))
        BootReceiver.markVpnActive(context)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertFalse(BootReceiver.shouldBeActive(context))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test
    fun an_update_with_protection_off_starts_nothing() {
        ShadowVpnService.setPrepareResult(null)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test
    fun other_broadcasts_are_ignored() {
        BootReceiver.markVpnActive(context)
        BootReceiver().onReceive(context, Intent("com.example.FAKE_BOOT"))
        assertTrue(BootReceiver.shouldBeActive(context))
    }
}
