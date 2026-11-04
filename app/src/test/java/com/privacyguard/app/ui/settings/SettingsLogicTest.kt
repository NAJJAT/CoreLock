package com.privacyguard.app.ui.settings

import com.privacyguard.app.R
import com.privacyguard.app.core.blocklist.BlocklistSource
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.vpn.KillSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN_SPEC_1.1.md §6.5. */
class SettingsLogicTest {

    @Test
    fun no_list_downloaded_yet_says_so() =
        assertEquals(SettingsLogic.ListsUpdated.Never, SettingsLogic.listsUpdated(listOf(0, 0, 0)))

    @Test
    fun updated_is_the_oldest_success_so_a_stale_list_is_not_hidden() {
        val at = SettingsLogic.listsUpdated(listOf(300, 100, 200)) as SettingsLogic.ListsUpdated.At
        assertEquals(100L, at.at)
        assertFalse(at.someMissing)
    }

    @Test
    fun a_list_that_never_downloaded_is_reported() {
        val at = SettingsLogic.listsUpdated(listOf(300, 0)) as SettingsLogic.ListsUpdated.At
        assertEquals(300L, at.at)
        assertTrue(at.someMissing)
    }

    @Test
    fun only_downloaded_lists_count_for_the_update_time() {
        // Built-in and user lists have no download, so they would always read "never".
        assertFalse(BlocklistSource.CUSTOM in SettingsLogic.DOWNLOADED_LISTS)
        assertFalse(BlocklistSource.OISD_FULL in SettingsLogic.DOWNLOADED_LISTS)
        assertTrue(BlocklistSource.HAGEZI_TIF_MINI in SettingsLogic.DOWNLOADED_LISTS)
    }

    @Test
    fun the_kill_switch_reads_on_only_when_android_says_so() {
        assertEquals(R.string.ui_set_ks_on, SettingsLogic.killSwitchSubtitle(KillSwitch.LockdownStatus.ON))
        // Never "On" or "Off" when Android could not answer (§5.7: never claim protection).
        assertEquals(R.string.ui_set_ks_not_running, SettingsLogic.killSwitchSubtitle(KillSwitch.LockdownStatus.NOT_RUNNING))
        assertEquals(R.string.ui_set_ks_unsupported, SettingsLogic.killSwitchSubtitle(KillSwitch.LockdownStatus.UNSUPPORTED))
        assertEquals(R.string.ui_set_ks_off, SettingsLogic.killSwitchSubtitle(KillSwitch.LockdownStatus.OFF))
    }

    @Test
    fun appearance_reads_the_stored_choice_or_the_default() {
        assertEquals(Appearance.LIGHT, SettingsLogic.appearanceOf("LIGHT"))
        assertEquals(Appearance.SYSTEM, SettingsLogic.appearanceOf("SYSTEM"))
        assertEquals(SettingsLogic.DEFAULT_APPEARANCE, SettingsLogic.appearanceOf(null))
        assertEquals(SettingsLogic.DEFAULT_APPEARANCE, SettingsLogic.appearanceOf("purple"))
    }

    @Test
    fun history_choices_are_1_0s() = assertEquals(listOf(7, 14, 30, 90), SettingsLogic.RETENTION_DAYS)
}
