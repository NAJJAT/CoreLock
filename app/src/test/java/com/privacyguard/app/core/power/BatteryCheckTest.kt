package com.privacyguard.app.core.power

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatteryCheckTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val power = shadowOf(app.getSystemService(PowerManager::class.java))
    private val activity = shadowOf(app.getSystemService(ActivityManager::class.java))

    @Test
    fun optimized_is_the_default() {
        assertEquals(BatteryCheck.State.OPTIMIZED, BatteryCheck.state(app))
    }

    @Test
    fun exempt_app_is_unrestricted() {
        power.setIgnoringBatteryOptimizations(app.packageName, true)
        assertEquals(BatteryCheck.State.UNRESTRICTED, BatteryCheck.state(app))
    }

    @Test
    fun restricted_background_use_wins_over_the_exemption() {
        power.setIgnoringBatteryOptimizations(app.packageName, true)
        activity.setBackgroundRestricted(true)
        assertEquals(BatteryCheck.State.RESTRICTED, BatteryCheck.state(app))
    }

    @Test
    @Config(sdk = [27])
    fun no_restricted_setting_before_android_9() {
        assertEquals(BatteryCheck.State.OPTIMIZED, BatteryCheck.state(app))
    }

    @Test
    fun opens_the_app_info_screen() {
        BatteryCheck.openSettings(app)
        val started: Intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${app.packageName}", started.dataString)
    }
}
