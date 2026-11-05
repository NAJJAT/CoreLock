package com.privacyguard.app.vpn

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.privacyguard.platform.android.NotificationHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowAlarmManager

/** D1: a pause always ends by itself, and never lasts longer than 5 minutes. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProtectionPauseTest {

    private val app: Context = RuntimeEnvironment.getApplication()
    private val now = 1_793_710_800_000L
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Before fun setUp() {
        ProtectionPause.resetForTest()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun tearDown() = ProtectionPause.resetForTest()

    private fun storePause(until: Long) {
        app.getSharedPreferences("privacyguard_pause", Context.MODE_PRIVATE).edit().putLong("until", until).commit()
        ProtectionPause.resetForTest()
    }

    // ── Pure decisions ───────────────────────────────────────────────────────

    @Test
    fun an_ended_pause_resumes_and_a_running_one_waits() {
        assertEquals(ProtectionPause.Decision.None, ProtectionPause.decide(null, now))
        assertEquals(ProtectionPause.Decision.ResumeNow, ProtectionPause.decide(now, now))
        assertEquals(ProtectionPause.Decision.ResumeNow, ProtectionPause.decide(now - 1, now))
        assertEquals(ProtectionPause.Decision.Wait(now + 60_000), ProtectionPause.decide(now + 60_000, now))
    }

    @Test
    fun moving_the_clock_back_never_stretches_a_pause() {
        // The clock went back an hour after pausing: the stored end looks 65 minutes away.
        val d = ProtectionPause.decide(now + 65 * 60_000L, now)
        assertEquals(ProtectionPause.Decision.Wait(now + ProtectionPause.PAUSE_MS), d)
    }

    @Test
    fun a_pause_is_offered_only_where_its_end_is_guaranteed() {
        val s = Build.VERSION_CODES.S
        assertEquals(ProtectionPause.Availability.AVAILABLE, ProtectionPause.availabilityOf(true, s, canScheduleExact = true, alwaysOn = false))
        assertEquals(ProtectionPause.Availability.NEEDS_EXACT_ALARMS, ProtectionPause.availabilityOf(true, s, canScheduleExact = false, alwaysOn = false))
        // Before Android 12 any alarm may start the service.
        assertEquals(ProtectionPause.Availability.AVAILABLE, ProtectionPause.availabilityOf(true, Build.VERSION_CODES.R, canScheduleExact = false, alwaysOn = false))
        assertEquals(ProtectionPause.Availability.ALWAYS_ON, ProtectionPause.availabilityOf(true, s, canScheduleExact = true, alwaysOn = true))
        assertEquals(ProtectionPause.Availability.NOT_RUNNING, ProtectionPause.availabilityOf(false, s, canScheduleExact = true, alwaysOn = false))
    }

    // ── Android side ─────────────────────────────────────────────────────────

    @Test
    fun pause_is_refused_while_protection_is_not_running() {
        assertTrue(!ProtectionPause.pause(app, now))
        assertNull(ProtectionPause.pausedUntil(app).value)
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test
    fun a_running_pause_found_at_app_start_gets_its_alarm_back() {
        // A force stop deletes the app's alarms; the next process start re-arms it.
        val until = System.currentTimeMillis() + 120_000
        storePause(until)
        ProtectionPause.recheck(app)
        val alarm = alarms.nextScheduledAlarm
        assertNotNull("the way back exists again", alarm)
        assertEquals(until, alarm!!.triggerAtTime)
        assertEquals(until, ProtectionPause.pausedUntil(app).value)
    }

    @Test
    fun without_exact_alarms_a_running_pause_ends_now_instead_of_maybe_never() {
        storePause(System.currentTimeMillis() + 120_000)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        ProtectionPause.recheck(app)
        assertNull(ProtectionPause.pausedUntil(app).value)
    }

    @Test
    fun an_overdue_pause_ends_and_protection_is_started_or_the_user_is_told() {
        storePause(System.currentTimeMillis() - 1_000)
        ProtectionPause.recheck(app)
        assertNull(ProtectionPause.pausedUntil(app).value)
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        val told = notifications.allNotifications.isNotEmpty()
        assertTrue("never silently off: the service starts, or 'Protection stopped' is shown", started != null || told)
    }

    @Test
    fun cancel_and_a_normal_start_both_end_the_pause_with_its_alarm_and_notification() {
        for (end in listOf<(Context) -> Unit>(ProtectionPause::cancel, ProtectionPause::onProtectionStarted)) {
            val until = System.currentTimeMillis() + 120_000
            storePause(until)
            ProtectionPause.recheck(app)
            NotificationHelper(app).postPaused(until)
            end(app)
            assertNull(ProtectionPause.pausedUntil(app).value)
            assertNull(alarms.nextScheduledAlarm)
            assertNull(notifications.getNotification(NotificationHelper.NOTIFICATION_ID_PAUSED))
        }
    }
}
