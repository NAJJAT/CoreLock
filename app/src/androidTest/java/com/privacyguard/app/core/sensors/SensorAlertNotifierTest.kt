package com.privacyguard.app.core.sensors

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensorAlertNotifierTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val tag = "com.example.alerttest"

    @Before
    fun setUp() {
        // Notifications must be allowed for the app to post (Android 13+).
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
    }

    @After
    fun tearDown() = manager.cancel(tag, 7_301)

    /** Posting is asynchronous: wait up to 2 s for our notifications to show up. */
    private fun posted(expectedCount: Int = 1): List<android.service.notification.StatusBarNotification> {
        val deadline = System.currentTimeMillis() + 2_000
        while (true) {
            val mine = manager.activeNotifications.filter { it.tag == tag }
            if (mine.size >= expectedCount || System.currentTimeMillis() > deadline) return mine
            Thread.sleep(50)
        }
    }

    private fun verdict(severity: Severity) =
        SensorVerdict(severity, "Example used the microphone while your screen was off.", setOf(Signal.SCREEN_OFF_OR_LOCKED))

    @Test
    fun criticalAlertIsPostedOnTheHighImportanceChannelWithActions() {
        val notifier = SensorAlertNotifier(context).also { it.createChannel() }
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(SensorAlertNotifier.CHANNEL_ID).importance)

        assertTrue(notifier.show(1, 99, SensorType.MIC, tag, "Microphone used", verdict(Severity.CRITICAL)))

        val posted = posted().single()
        assertEquals(SensorAlertNotifier.CHANNEL_ID, posted.notification.channelId)
        assertEquals(listOf("Show timeline", "Review permissions", "It was me"), posted.notification.actions.map { it.title.toString() })
    }

    @Test
    fun belowHighIsNotPostedAndRepeatsAreGrouped() {
        val notifier = SensorAlertNotifier(context)
        assertFalse(notifier.show(1, 1, SensorType.MIC, tag, "Microphone used", verdict(Severity.LOW)))

        assertTrue(notifier.show(1, 1, SensorType.MIC, tag, "Microphone used", verdict(Severity.HIGH)))
        // The same use again at the same severity: nothing new.
        assertFalse(notifier.show(1, 1, SensorType.MIC, tag, "Microphone used", verdict(Severity.HIGH)))
        // A second use by the same app updates the one notification with a count.
        assertTrue(notifier.show(2, 2, SensorType.MIC, tag, "Microphone used", verdict(Severity.HIGH)))

        Thread.sleep(500) // let the update land
        val posted = posted()
        assertEquals(1, posted.size)
        assertTrue(posted.single().notification.extras.getCharSequence("android.text").toString().contains("2 times"))
    }
}
