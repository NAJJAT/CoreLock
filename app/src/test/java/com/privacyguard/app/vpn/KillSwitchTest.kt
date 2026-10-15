package com.privacyguard.app.vpn

import android.app.NotificationManager
import android.content.Context
import com.privacyguard.app.R
import com.privacyguard.platform.android.NotificationHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The drop alert said "Android is blocking all connections" after the user had
 * turned lockdown and Always-on off: the lockdown state was cached when the tunnel
 * started. It must only say so while Android reports lockdown for our service.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class KillSwitchTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = KillSwitch.detach()

    @Test
    fun no_lockdown_without_a_running_service() {
        KillSwitch.detach()
        assertFalse(KillSwitch.lockdownActive)
    }

    @Test
    fun drop_alert_without_lockdown_says_traffic_is_unfiltered() {
        KillSwitch.detach()

        NotificationHelper(context).also { it.createChannels() }.postKillSwitchAlert()

        val n = shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(NotificationHelper.NOTIFICATION_ID_KILL_SWITCH)
        assertEquals(context.getString(R.string.notif_vpn_down_open),
            n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
    }
}
