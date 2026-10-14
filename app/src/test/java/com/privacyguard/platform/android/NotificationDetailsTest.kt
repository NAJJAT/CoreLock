package com.privacyguard.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * By default a notification says only what kind of alert it is, never which app or
 * server: anything in the text reaches the lock screen and every app with
 * notification access. "Show details in notifications" (off) brings them back.
 */
class NotificationDetailsTest {

    private val src = File("src/main/java/com/privacyguard/platform/android/NotificationHelper.kt").readText()

    /** Each `fun post…` / `fun show…` body, by name. */
    private val methods: Map<String, String> =
        Regex("""\n    fun ((?:post|show)\w+)\(""").findAll(src).toList().let { found ->
            found.mapIndexed { i, m ->
                val end = found.getOrNull(i + 1)?.range?.first ?: src.length
                m.groupValues[1] to src.substring(m.range.first, end)
            }.toMap()
        }

    private val names = Regex("""appLabel|appName|domain|destination|sni|malwareName|topApp""")

    @Test
    fun every_alert_naming_an_app_or_host_hides_it_by_default() {
        assertTrue(methods.size >= 8)
        val leaking = methods.filter { (_, body) ->
            names.containsMatchIn(body.substringAfter(")")) &&
                !body.contains(".detailsOnlyIfAllowed(") && !body.contains("showDetails()") &&
                !body.contains("postWeeklyReport(")          // the wrapper delegates
        }.keys
        assertEquals("alerts that put names in the text without the details gate", emptySet<String>(), leaking)
    }

    @Test
    fun details_are_off_by_default() {
        val prefs = File("src/main/java/com/privacyguard/app/data/local/preferences/SettingsPreferences.kt").readText()
        assertTrue(prefs.contains("prefs.getBoolean(KEY_NOTIFICATION_DETAILS, false)"))
    }

    @Test
    fun the_generic_text_names_nothing() {
        for (dir in listOf("values", "values-nl", "values-fr", "values-ar")) {
            val xml = File("src/main/res/$dir/strings_notifications.xml").readText()
            for (key in listOf("notif_open_for_details", "notif_kind_malware", "notif_kind_tls_fp",
                "notif_kind_behavior", "notif_kind_cleartext", "notif_kind_ct")) {
                val value = Regex("""<string name="$key">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
                assertTrue("$dir/$key missing", value != null)
                assertTrue("$dir/$key has a placeholder", !value!!.contains("%"))
            }
        }
    }
}
