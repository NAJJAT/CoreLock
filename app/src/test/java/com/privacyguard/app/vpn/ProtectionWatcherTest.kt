package com.privacyguard.app.vpn

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.privacyguard.app.PrivacyGuardApplication
import com.privacyguard.platform.android.NotificationHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowBinder

/**
 * V-18, cheaper watchdog: the :watchdog process learns of the VPN process's death
 * from the binder driver, so nothing is scheduled while protection is healthy.
 * Robolectric cannot kill a process; these tests cover what happens around it.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class ProtectionWatcherTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private fun stoppedNotification() = shadowOf(context.getSystemService(NotificationManager::class.java))
        .getNotification(NotificationHelper.NOTIFICATION_ID_PROTECTION_STOPPED)

    @After
    fun tearDown() {
        ProtectionWatcher.disarm()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowBinder.reset()
    }

    @Test
    fun death_with_exact_alarms_sets_one_exact_restart_alarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        ProtectionWatchdog.onProtectionProcessDied(context)

        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertTrue("exact", alarm.isAllowWhileIdle)
        assertNull("no notification when a restart is coming", stoppedNotification())
    }

    @Test
    fun death_without_exact_alarms_says_protection_stopped_at_once() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        ProtectionWatchdog.onProtectionProcessDied(context)

        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertNotNull(stoppedNotification())
    }

    @Test
    fun backup_alarm_never_wakes_the_phone() {
        ProtectionWatchdog.armBackup(context)

        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarm.type)
    }

    @Test
    fun a_deliberate_stop_cancels_every_alarm() {
        ProtectionWatchdog.armBackup(context)
        ProtectionWatchdog.onProtectionProcessDied(context)

        ProtectionWatchdog.disarm(context)

        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun watcher_takes_the_token_and_a_disarm_releases_it() {
        val binder = WatcherBinder(context)

        assertTrue(transact(binder, ProtectionWatcher.WATCH, Binder()))
        assertTrue(ProtectionWatcher.isWatching())

        assertTrue(transact(binder, ProtectionWatcher.DISARM, null))
        assertFalse(ProtectionWatcher.isWatching())
    }

    @Test
    fun watcher_ignores_other_apps() {
        ShadowBinder.setCallingUid(android.os.Process.myUid() + 1)

        assertFalse(transact(WatcherBinder(context), ProtectionWatcher.WATCH, Binder()))
        assertFalse(ProtectionWatcher.isWatching())
    }

    @Test
    fun only_the_watchdog_process_skips_app_setup() {
        assertTrue(PrivacyGuardApplication.isWatchdogProcess("com.privacyguard.app.enterprise:watchdog"))
        assertFalse(PrivacyGuardApplication.isWatchdogProcess("com.privacyguard.app.enterprise"))
        assertFalse(PrivacyGuardApplication.isWatchdogProcess(null))
    }

    @Test
    fun watcher_service_runs_in_its_own_process() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, ProtectionWatcherService::class.java), 0)
        assertTrue(info.processName, PrivacyGuardApplication.isWatchdogProcess(info.processName))
        assertFalse(info.exported)
    }

    private fun transact(binder: Binder, code: Int, token: IBinder?): Boolean {
        val data = Parcel.obtain()
        return try {
            if (token != null) data.writeStrongBinder(token)
            data.setDataPosition(0)
            binder.transact(code, data, null, 0)
        } finally {
            data.recycle()
        }
    }
}
