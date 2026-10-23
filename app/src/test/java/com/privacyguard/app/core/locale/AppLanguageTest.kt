package com.privacyguard.app.core.locale

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.view.View
import com.privacyguard.app.MainActivity
import com.privacyguard.app.R
import com.privacyguard.platform.android.NotificationHelper
import com.privacyguard.vpn.interception.InspectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * Settings › Language: the choice is saved and restored, and non-activity code (the VPN
 * notification) follows it on every Android version, including below 13 where AppCompat
 * applies it to activities only.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default installs Conscrypt JVM-wide, which broke the plain-JVM TLS
// tests (DohHostnameVerificationTest) that run later in the same fork.
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class AppLanguageTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        context.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ── Saving and restoring ────────────────────────────────────────────────

    @Test
    fun a_choice_is_saved_and_restored() {
        assertNull("never chosen", AppLanguage.saved(context))
        for (tag in AppLanguage.SUPPORTED + "") {
            AppLanguage.save(context, tag)
            assertEquals(tag, AppLanguage.saved(context))
        }
    }

    /** Stored in a preferences file, so it survives an app restart and a reboot. */
    @Test
    fun the_choice_is_on_disk_in_its_own_preferences_file() {
        AppLanguage.save(context, "ar")
        assertEquals("ar", context.getSharedPreferences("app_language", Context.MODE_PRIVATE).getString("tag", null))
    }

    @Test
    fun an_unsupported_language_means_the_system_language() {
        AppLanguage.save(context, "de")
        assertEquals("", AppLanguage.saved(context))
        context.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().putString("tag", "xx").commit()
        assertEquals("", AppLanguage.saved(context))
    }

    // ── Which source wins ───────────────────────────────────────────────────

    @Test
    fun android_13_and_newer_trust_the_platform_value() {
        // Also after a change in the system "App language" screen, which the stored copy misses.
        assertEquals("fr", AppLanguage.resolve(sdk = 33, appCompatTag = "fr", savedTag = "ar"))
        assertEquals("", AppLanguage.resolve(sdk = 34, appCompatTag = "", savedTag = "ar"))
    }

    @Test
    fun below_13_the_stored_choice_wins_and_appcompat_only_fills_in() {
        // AppCompat may still hold the old value while a change is being applied.
        assertEquals("ar", AppLanguage.resolve(sdk = 27, appCompatTag = "fr", savedTag = "ar"))
        assertEquals("", AppLanguage.resolve(sdk = 27, appCompatTag = "fr", savedTag = ""))
        // A choice made before the stored copy existed.
        assertEquals("nl", AppLanguage.resolve(sdk = 32, appCompatTag = "nl", savedTag = null))
        assertEquals("", AppLanguage.resolve(sdk = 32, appCompatTag = "", savedTag = null))
    }

    // ── Non-activity contexts follow the choice ─────────────────────────────

    private fun stringIn(tag: String, id: Int): String {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config).getString(id)
    }

    @Test
    @Config(sdk = [27])
    fun below_13_a_context_follows_the_choice_and_arabic_is_rtl() {
        AppLanguage.save(context, "ar")
        val arabic = AppLanguage.localized(context)
        assertEquals(stringIn("ar", R.string.notif_active), arabic.getString(R.string.notif_active))
        assertEquals(View.LAYOUT_DIRECTION_RTL, arabic.resources.configuration.layoutDirection)

        AppLanguage.save(context, "fr")
        val french = AppLanguage.localized(context)
        assertEquals(View.LAYOUT_DIRECTION_LTR, french.resources.configuration.layoutDirection)

        AppLanguage.save(context, "")
        assertSame("system language: the context itself", context, AppLanguage.localized(context))
    }

    @Test
    @Config(sdk = [27])
    fun the_vpn_notification_follows_a_language_change_without_a_new_helper() {
        val helper = NotificationHelper(context).also { it.createChannels() }
        fun title() = helper.buildVpnActiveNotification(0, MainActivity::class.java, InspectionStatus.OFF)
            .extras.getCharSequence(Notification.EXTRA_TITLE).toString()

        AppLanguage.save(context, "ar")
        assertEquals(stringIn("ar", R.string.notif_active), title())
        AppLanguage.save(context, "nl")
        assertEquals(stringIn("nl", R.string.notif_active), title())
        assertNotEquals(stringIn("ar", R.string.notif_active), title())
    }

    // ── One list everywhere ─────────────────────────────────────────────────

    @Test
    fun the_picker_list_matches_locales_config_and_the_translations() {
        val config = File("src/main/res/xml/locales_config.xml").readText()
        val declared = Regex("""android:name="([a-z]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        assertEquals(AppLanguage.SUPPORTED, declared)
        for (tag in AppLanguage.SUPPORTED - "en") {
            assertEquals("values-$tag exists", true, File("src/main/res/values-$tag").isDirectory)
        }
    }
}
