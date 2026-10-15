package com.privacyguard.vpn.firewall

import android.content.Context
import com.privacyguard.app.ui.traffic.AppNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Traffic from adb shell (UID 2000) showed as "Removed app": getNameForUid returns
 * the shared-user name "android.uid.shell:2000", which is no package.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class ShellUidLabelTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun the_shell_uid_resolves_to_the_shell_package_in_every_user() {
        val filter = AppFilter { uid -> "android.uid.shell:$uid" }
        assertEquals(AppFilter.SHELL_PACKAGE, filter.packageForUid(2000))
        assertEquals(AppFilter.SHELL_PACKAGE, filter.packageForUid(10 * 100_000 + 2000))
        assertEquals("android.uid.shell:10123", filter.packageForUid(10123))
    }

    @Test
    fun shell_rows_old_and_new_are_labelled_adb_shell() {
        for (pkg in listOf("com.android.shell", "android.uid.shell:2000")) {
            assertTrue(pkg, AppFilter.isShell(pkg))
            assertEquals("ADB / shell", AppNames.label(context, pkg))
        }
        assertFalse(AppFilter.isShell("com.android.shellfish"))
    }

    @Test
    fun an_uninstalled_app_is_still_a_removed_app() {
        assertEquals("Removed app", AppNames.label(context, "com.example.gone"))
    }
}
