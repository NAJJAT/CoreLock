package com.privacyguard.app.vpn

import com.privacyguard.app.vpn.KillSwitch.LockdownStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Only Android's Always-on VPN with "Block connections without VPN" blocks traffic
 * once the tunnel is gone, and Android tells only the running VPN service whether
 * it is on. So the app reports it as on only when Android said so, and as "not
 * checked" (never as on, never as a plain "off") when it could not ask.
 *
 * The in-app "VPN drop alert" switch was removed: it could not fire when protection
 * actually stopped, so it promised something it did not do.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class KillSwitchTest {

    @After
    fun tearDown() = KillSwitch.detach()

    @Test
    fun on_only_when_android_reports_lockdown_for_the_running_service() {
        assertEquals(LockdownStatus.ON, KillSwitch.statusOf(sdk = 29, lockdown = true))
        assertEquals(LockdownStatus.OFF, KillSwitch.statusOf(sdk = 29, lockdown = false))
    }

    @Test
    fun not_checked_without_a_running_service_or_before_android_10() {
        assertEquals(LockdownStatus.NOT_RUNNING, KillSwitch.statusOf(sdk = 34, lockdown = null))
        assertEquals(LockdownStatus.UNSUPPORTED, KillSwitch.statusOf(sdk = 28, lockdown = null))
        assertEquals(LockdownStatus.UNSUPPORTED, KillSwitch.statusOf(sdk = 28, lockdown = true))
    }

    @Test
    fun detached_means_not_checked() {
        KillSwitch.detach()
        assertFalse(KillSwitch.lockdownActive)
        val status = KillSwitch.lockdownStatus
        assert(status == LockdownStatus.NOT_RUNNING || status == LockdownStatus.UNSUPPORTED) { status }
    }

    private fun src(path: String) = File("src/main/java/com/privacyguard/$path").readText()

    @Test
    fun the_drop_alert_switch_and_its_notification_are_gone() {
        assertFalse(src("app/ui/settings/SettingsScreen.kt").contains("set_vpn_drop_alert"))
        assertFalse(src("platform/android/NotificationHelper.kt").contains("postKillSwitchAlert"))
        assertFalse(src("app/ui/dashboard/DashboardViewModel.kt").contains("killSwitchEnabled"))
        assertFalse(src("app/ui/dashboard/DashboardViewModel.kt").contains("dash_rec_kill_switch"))
    }

    @Test
    fun screens_use_the_three_state_status() {
        for (path in listOf("app/ui/dashboard/DashboardViewModel.kt", "app/ui/settings/SettingsScreen.kt", "app/ui/settings/SettingsViewModel.kt")) {
            assertFalse("$path still reads the two-state lockdownActive", src(path).contains("lockdownActive"))
        }
    }
}
