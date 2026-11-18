package com.privacyguard.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Settings "Notifications" switch must silence every alert and summary. Each
 * post function checks it first. Exempt: "Protection stopped" (after the process
 * died, or after Always-on was turned off; postStopped is their shared body); both
 * say that protection is off. Also exempt:
 * the notice that the user's organisation turned on HTTPS inspection. Muting it
 * would make decryption silent again (security review finding #1). And the pause
 * notice (D1): it says protection is off for now and carries "Resume now".
 */
class NotificationSwitchTest {

    private val exempt = setOf("postProtectionStopped", "postStopped", "postInspectionEnabledByOrganisation", "postPaused")

    @Test
    fun every_alert_checks_the_switch_first() {
        val src = File("src/main/java/com/privacyguard/platform/android/NotificationHelper.kt").readText()
        val posts = Regex("""fun (post\w+)\([^)]*\)\s*\{\s*([^\n]*)""").findAll(src)
            .map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
        assertTrue("found ${posts.size} post functions", posts.size >= 9)
        val ungated = posts.filter { (name, first) -> name !in exempt && first != "if (muted()) return" }.map { it.first }
        assertEquals(emptyList<String>(), ungated)
    }

    /**
     * The blocklist update notice, which checked the switch itself, was removed in bug-fix
     * round 2 (B-2). Now every notification is posted by NotificationHelper, so the test
     * above covers them all; a post anywhere else would escape it.
     */
    @Test
    fun nothing_outside_the_helper_posts_a_notification() {
        val outside = listOf("src/main/java", "src/enterprise/java").flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "NotificationHelper.kt" }
                .filter { Regex("""\.notify\(""").containsMatchIn(it.readText()) }.map { it.path }.toList()
        }
        assertEquals(emptyList<String>(), outside)
    }
}
