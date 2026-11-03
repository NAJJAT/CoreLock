package com.privacyguard.app.core.power

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

@RunWith(RobolectricTestRunner::class)
class SystemStopCheckTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun exited(process: String, reason: Int, at: Long) {
        val am = context.getSystemService(ActivityManager::class.java)
        shadowOf(am).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder().setProcessName(process).setReason(reason).setTimestamp(at).build()
        )
    }

    @Test
    fun only_kills_by_the_system_count_never_a_crash_or_the_user() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_FREEZER,
        )) assertEquals("reason $reason", SystemStopCheck.Result.SYSTEM, SystemStopCheck.classify(reason))
        for (reason in listOf(
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            999, // a reason from a newer Android: never claimed as a kill
        )) assertEquals("reason $reason", SystemStopCheck.Result.NOT_SYSTEM, SystemStopCheck.classify(reason))
        assertEquals(SystemStopCheck.Result.UNKNOWN, SystemStopCheck.classify(ApplicationExitInfo.REASON_UNKNOWN))
    }

    @Test
    fun the_main_process_record_decides_not_the_watchdog() {
        exited(context.packageName, ApplicationExitInfo.REASON_LOW_MEMORY, at = 1_000)
        exited("${context.packageName}:watchdog", ApplicationExitInfo.REASON_CRASH, at = 2_000)
        assertEquals(SystemStopCheck.Result.SYSTEM, SystemStopCheck.lastExit(context))
    }

    @Test
    fun a_crash_of_the_main_process_is_not_a_system_stop() {
        exited(context.packageName, ApplicationExitInfo.REASON_CRASH, at = 1_000)
        assertEquals(SystemStopCheck.Result.NOT_SYSTEM, SystemStopCheck.lastExit(context))
    }

    @Test
    fun no_record_is_unknown() {
        assertEquals(SystemStopCheck.Result.UNKNOWN, SystemStopCheck.lastExit(context))
    }

    @Test
    @Config(sdk = [29])
    fun android_10_and_older_keep_no_records() {
        assertEquals(SystemStopCheck.Result.UNKNOWN, SystemStopCheck.lastExit(context))
    }
}
