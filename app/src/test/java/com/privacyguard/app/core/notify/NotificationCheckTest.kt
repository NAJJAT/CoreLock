package com.privacyguard.app.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import com.privacyguard.platform.android.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * N-4: "Protection stopped" is a notification on the alerts channel. When the app's
 * notifications or that channel are off, a crash ends protection with no signal, so Home
 * must say so before the user relies on it.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class NotificationCheckTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun the_decision() {
        assertFalse(NotificationCheck.blocked(appEnabled = true, alertsImportance = null))
        assertFalse(NotificationCheck.blocked(appEnabled = true, alertsImportance = NotificationManager.IMPORTANCE_HIGH))
        assertFalse(NotificationCheck.blocked(appEnabled = true, alertsImportance = NotificationManager.IMPORTANCE_LOW))
        assertTrue(NotificationCheck.blocked(appEnabled = false, alertsImportance = NotificationManager.IMPORTANCE_HIGH))
        assertTrue(NotificationCheck.blocked(appEnabled = true, alertsImportance = NotificationManager.IMPORTANCE_NONE))
    }

    @Test
    fun app_notifications_off_is_blocked() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertTrue(NotificationCheck.isBlocked(context))
        shadowOf(manager).setNotificationsEnabled(true)
        assertFalse(NotificationCheck.isBlocked(context))
    }

    @Test
    fun alerts_channel_turned_off_is_blocked() {
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(
            NotificationChannel(NotificationHelper.CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_NONE))
        assertTrue(NotificationCheck.isBlocked(context))
    }

    @Test
    fun the_button_opens_this_apps_notification_settings() {
        NotificationCheck.openSettings(context)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
