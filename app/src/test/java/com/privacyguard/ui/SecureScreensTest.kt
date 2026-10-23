package com.privacyguard.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for V-08: FLAG_SECURE was only set on devices that looked
 * compromised. Every screen with collected data is secure, always.
 */
class SecureScreensTest {

    @After fun reset() {
        SecureScreens.deviceForcesSecure = false
        SecureScreens.debugBuild = false
        SecureScreens.userAllowsScreenshots = false
    }

    private val routes: List<String> by lazy {
        val src = File("src/main/java/com/privacyguard/ui/AppNavHost.kt").readText()
        Regex("composable\\(\\s*\"([^\"]+)\"").findAll(src).map { it.groupValues[1] }.toList()
    }

    @Test fun every_route_but_onboarding_is_secure() {
        assertTrue("routes not found", routes.size >= 15)
        val open = routes.filterNot { SecureScreens.shouldSecure(it) }
        assertEquals(listOf("onboarding"), open)
    }

    @Test fun unknown_and_initial_screens_are_secure() {
        assertTrue(SecureScreens.shouldSecure(null))
        assertTrue(SecureScreens.shouldSecure("someNewScreen/{id}"))
        assertTrue(SecureScreens.shouldSecure("appDetail/{packageName}?appName={appName}"))
    }

    @Test fun a_risky_device_secures_onboarding_too() {
        SecureScreens.deviceForcesSecure = true
        assertTrue(SecureScreens.shouldSecure("onboarding"))
    }

    @Test fun debug_builds_never_set_it() {
        SecureScreens.debugBuild = true
        SecureScreens.deviceForcesSecure = true
        assertFalse(SecureScreens.shouldSecure("dashboard"))
        assertFalse(SecureScreens.shouldSecure(null))
    }

    @Test fun allow_screenshots_turns_it_off_everywhere_and_is_off_by_default() {
        assertTrue(SecureScreens.shouldSecure("connections"))
        SecureScreens.userAllowsScreenshots = true
        assertTrue(routes.none { SecureScreens.shouldSecure(it) })
        val prefs = File("src/main/java/com/privacyguard/app/data/local/preferences/SettingsPreferences.kt").readText()
        assertTrue(prefs.contains("prefs.getBoolean(KEY_ALLOW_SCREENSHOTS, false)"))
    }

    @Test fun the_nav_host_applies_it_on_every_destination_change_and_switch() {
        val src = File("src/main/java/com/privacyguard/ui/AppNavHost.kt").readText()
        assertTrue(src.contains("LaunchedEffect(currentDest?.route, allowScreenshots)"))
        assertTrue(src.contains("SecureScreens.apply(it.window, currentDest?.route)"))
        val base = File("src/main/java/com/privacyguard/BaseActivity.kt").readText()
        assertTrue(base.contains("SecureScreens.debugBuild = com.privacyguard.app.BuildConfig.DEBUG"))
        assertTrue(base.contains("SecureScreens.apply(window, route = null)"))
    }

    /** Regression for V-09: no notification is built without the private lock-screen setup. */
    @Test fun every_notification_goes_through_private_notifications() {
        val offenders = listOf("src/main/java", "src/enterprise/java").map(::File).filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" && f.name != "PrivateNotifications.kt" }.toList() }
            .filter { it.readText().contains("NotificationCompat.Builder(") || it.readText().contains("Notification.Builder(") }
        assertEquals(emptyList<File>(), offenders)
        val factory = File("src/main/java/com/privacyguard/platform/android/PrivateNotifications.kt").readText()
        assertTrue(factory.contains("VISIBILITY_PRIVATE") && factory.contains("setPublicVersion"))
    }
}
