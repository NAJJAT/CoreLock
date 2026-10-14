package com.privacyguard.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings "Notifications" switch must silence every alert and summary. Each
 * post function checks it first. Exempt: the VPN drop alert (its own switch) and
 * "Protection stopped" after the process died; both say that protection is off.
 */
class NotificationSwitchTest {

    private val exempt = setOf("postKillSwitchAlert", "postProtectionStopped")

    @Test
    fun every_alert_checks_the_switch_first() {
        val src = File("src/main/java/com/privacyguard/platform/android/NotificationHelper.kt").readText()
        val posts = Regex("""fun (post\w+)\([^)]*\)\s*\{\s*([^\n]*)""").findAll(src)
            .map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        assertTrue("found ${posts.size} post functions", posts.size >= 9)
        val ungated = posts.filter { (name, first) -> name !in exempt && first != "if (muted()) return" }.map { it.first }
        assertEquals(emptyList<String>(), ungated)
    }

    @Test
    fun the_blocklist_update_notice_checks_it_too() {
        val src = File("src/main/java/com/privacyguard/app/service/notification/NotificationService.kt").readText()
        assertTrue(src.contains("notificationsEnabled.value) return"))
    }
}
