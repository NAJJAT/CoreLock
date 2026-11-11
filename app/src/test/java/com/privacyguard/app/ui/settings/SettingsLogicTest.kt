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
    fun lists_kept_from_1_0_0_without_a_date_are_loaded_not_never() {
        // 1.0.0 stored no per-list time: after the update the names are there but every time is 0.
        assertEquals(
            SettingsLogic.ListsUpdated.DateUnknown(someMissing = false),
            SettingsLogic.listsUpdated(listOf(0, 0, 0), stored = listOf(true, true, true)),
        )
        assertEquals(
            SettingsLogic.ListsUpdated.DateUnknown(someMissing = true),
            SettingsLogic.listsUpdated(listOf(0, 0, 0), stored = listOf(true, false, true)),
        )
    }

    @Test
    fun one_undated_list_makes_the_oldest_date_unknown() {
        // "Updated 2 days ago" would claim the undated list is that fresh: it may be months old.
        assertEquals(
            SettingsLogic.ListsUpdated.DateUnknown(someMissing = false),
            SettingsLogic.listsUpdated(listOf(300, 0), stored = listOf(true, true)),
        )
    }

    @Test
    fun nothing_stored_and_nothing_dated_is_still_never() =
        assertEquals(SettingsLogic.ListsUpdated.Never, SettingsLogic.listsUpdated(listOf(0, 0), stored = listOf(false, false)))

    @Test
    fun a_list_is_stored_when_its_own_rows_are_in_the_database() {
        val stored = SettingsLogic.storedFlags(
            listOf(BlocklistSource.HAGEZI_PRO_MINI, BlocklistSource.HAGEZI_TIF_MINI, BlocklistSource.HAGEZI_DOH),
            // The bundled malware copy and the built-in names are not the downloaded list.
            mapOf("Hagezi-Pro-mini" to 120_000, "Snapshot-Hagezi-TIF-mini@2026-11-05" to 9_000, "BuiltIn-malware" to 5, "Hagezi-DoH" to 0),
        )
        assertEquals(listOf(true, false, false), stored)
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
