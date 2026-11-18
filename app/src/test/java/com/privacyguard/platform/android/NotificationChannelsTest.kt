package com.privacyguard.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bug-fix round 2 (B-2): the "blocklist updated" notification was posted to a channel
 * that no version ever created, so Android 8 and newer dropped it, and Android 7 showed it
 * with untranslated text. Every notification must use a channel [NotificationHelper]
 * creates.
 */
class NotificationChannelsTest {

    private val sources = listOf("src/main/java", "src/enterprise/java").flatMap { root ->
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun every_notification_uses_a_channel_that_is_created() {
        val helper = File("src/main/java/com/privacyguard/platform/android/NotificationHelper.kt").readText()
        val created = Regex("""NotificationChannel\(\s*(CHANNEL_\w+)""").findAll(helper).map { it.groupValues[1] }.toSet()
        assertTrue("found the created channels: $created", created.size >= 3)

        val used = sources.flatMap { f ->
            Regex("""PrivateNotifications\.builder\(\s*\w+\s*,\s*(\w+)\s*\)|\bbuilder\(\s*context\s*,\s*(\w+)\s*\)""")
                .findAll(f.readText()).map { m -> f.name to (m.groupValues[1].ifEmpty { m.groupValues[2] }) }.toList()
        }
        assertTrue("found the notifications", used.isNotEmpty())
        val unknown = used.filter { (_, channel) -> channel !in created && channel != "channelId" }
        assertEquals("posted to a channel that is never created", emptyList<Pair<String, String>>(), unknown)
    }
}
