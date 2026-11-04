package com.privacyguard.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.privacyguard.app.BuildConfig
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.app.vpn.KillSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** DESIGN_SPEC_1.1.md §6.5 and the Settings rows of the parity table (§5.6). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val now = 10L * 24 * 60 * 60 * 1000

    private fun show(state: SettingsHomeState = SettingsHomeState(), actions: SettingsActions = SettingsActions()) = rule.setContent {
        CoreLockTheme(Appearance.DARK) { SettingsHomeContent(state, language = "System default", actions = actions, now = now) }
    }

    private fun scrollTo(text: String) = rule.onNodeWithTag(TAG_SETTINGS_LIST).performScrollToNode(hasText(text))

    @Test
    fun every_settings_row_of_the_spec_is_there() {
        show()
        listOf(
            "Protection level", "Rules and allowed sites", "Blocklists", "Kill switch", "Restart after a crash",
            "Notifications", "Show details", "Weekly summary", "Language", "Theme",
            "Keep history for", "Export history", "Allow screenshots", "Clear all data",
            "Version", "What CoreLock can and can't do", "Blocking policy", "Open-source licenses",
            "See new versions", "Report a problem", "Advanced",
        ).forEach { title ->
            scrollTo(title)
            assertTrue(title, rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun the_kill_switch_is_never_called_on_without_android_saying_so() {
        show(SettingsHomeState(lockdown = KillSwitch.LockdownStatus.NOT_RUNNING))
        rule.onNodeWithText("Turn protection on to check").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("On: no internet without CoreLock").fetchSemanticsNodes().size)
    }

    @Test
    fun clear_all_data_asks_first_and_says_what_stays() {
        var cleared = false
        show(actions = SettingsActions(onClear = { cleared = true }))
        rule.onNodeWithTag(TAG_SETTINGS_LIST).performScrollToNode(hasTestTag(TAG_CLEAR_ROW))
        rule.onNodeWithText("Deletes history and statistics. Settings, rules and blocklists stay.").assertIsDisplayed()
        rule.onNodeWithTag(TAG_CLEAR_ROW).performClick()
        assertFalse("the row alone must not delete", cleared)
        rule.onNodeWithTag(TAG_CLEAR_CONFIRM).performClick()
        assertTrue(cleared)
    }

    @Test
    fun export_warns_that_the_file_is_private_before_writing_it() {
        var exported = false
        show(actions = SettingsActions(onExport = { exported = true }))
        scrollTo("Export history")
        rule.onNodeWithText("Export history").performClick()
        assertFalse(exported)
        rule.onNodeWithText("Keep it private", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Export").performClick()
        assertTrue(exported)
    }

    @Test
    fun theme_offers_system_light_and_dark() {
        var chosen: Appearance? = null
        show(SettingsHomeState(appearance = Appearance.DARK), SettingsActions(onAppearance = { chosen = it }))
        rule.onNodeWithTag(TAG_SETTINGS_LIST).performScrollToNode(hasTestTag(TAG_THEME_ROW))
        rule.onNodeWithTag(TAG_THEME_ROW).performClick()
        rule.onNodeWithText("System").assertIsDisplayed()
        rule.onNodeWithText("Light").performClick()
        assertEquals(Appearance.LIGHT, chosen)
    }

    @Test
    fun history_keeps_1_0s_choices() {
        var days: Int? = null
        show(SettingsHomeState(retentionDays = 30), SettingsActions(onRetention = { days = it }))
        scrollTo("Keep history for")
        rule.onNodeWithText("Keep history for").performClick()
        listOf("7 days", "14 days", "90 days").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("90 days").performClick()
        assertEquals(90, days)
    }

    @Test
    fun blocklists_row_says_when_nothing_downloaded_yet() {
        show(SettingsHomeState(lists = SettingsLogic.ListsUpdated.Never))
        rule.onNodeWithText("Not downloaded yet. Built-in lists are used.").assertIsDisplayed()
    }

    @Test
    fun blocklists_row_says_how_long_ago() {
        show(SettingsHomeState(lists = SettingsLogic.ListsUpdated.At(now - 2L * 24 * 60 * 60 * 1000, someMissing = false)))
        rule.onNodeWithText("Updated 2 days ago").assertIsDisplayed()
    }

    @Test
    fun version_shows_the_build_and_opens_nothing() {
        show()
        scrollTo("Version")
        rule.onNodeWithText(BuildConfig.VERSION_NAME).assertIsDisplayed()
    }

    @Test
    fun report_a_problem_opens_the_issue_templates_in_the_browser() {
        var url: String? = null
        show(actions = SettingsActions(onOpenUrl = { url = it }))
        scrollTo("Report a problem")
        rule.onNodeWithText("Report a problem").performClick()
        assertEquals("https://github.com/NAJJAT/CoreLock/issues/new/choose", url)
    }

    @Test
    fun advanced_opens_the_expert_settings() {
        var opened = false
        show(actions = SettingsActions(nav = SettingsNav(onOpenAdvanced = { opened = true })))
        rule.onNodeWithTag(TAG_SETTINGS_LIST).performScrollToNode(hasTestTag(TAG_ADVANCED_ROW))
        rule.onNodeWithTag(TAG_ADVANCED_ROW).performClick()
        assertTrue(opened)
    }

    @Test
    fun kill_switch_screen_states_the_status_and_opens_android() {
        var opened = false
        rule.setContent { CoreLockTheme(Appearance.DARK) { KillSwitchContent(KillSwitch.LockdownStatus.ON, {}, { opened = true }) } }
        rule.onNodeWithText("On: no internet without CoreLock").assertIsDisplayed()
        rule.onNodeWithTag(TAG_KS_OPEN).performClick()
        assertTrue(opened)
    }

    @Test
    fun refresh_is_disabled_while_lists_download() {
        rule.setContent {
            CoreLockTheme(Appearance.DARK) { BlocklistsContent(SettingsHomeState(listsUpdating = true, listNames = 1234), {}, {}, now) }
        }
        rule.onNodeWithTag(TAG_LISTS_REFRESH).assertIsNotEnabled()
        rule.onNodeWithText("Downloading the newest lists…").assertIsDisplayed()
    }

    @Test
    fun refresh_is_offered_when_idle() {
        var refreshed = false
        rule.setContent {
            CoreLockTheme(Appearance.DARK) { BlocklistsContent(SettingsHomeState(lists = SettingsLogic.ListsUpdated.Never), {}, { refreshed = true }, now) }
        }
        rule.onNodeWithTag(TAG_LISTS_REFRESH).assertIsEnabled().performClick()
        assertTrue(refreshed)
    }

    // ── D2: export and import of rules and settings ─────────────────────────

    private fun showImport(importUi: ImportUi?, actions: SettingsActions = SettingsActions()) = rule.setContent {
        CoreLockTheme(Appearance.DARK) { SettingsHomeContent(SettingsHomeState(), "System default", actions, now, importUi) }
    }

    @Test
    fun exporting_rules_says_the_file_is_private_before_the_picker_opens() {
        var picked = false
        show(actions = SettingsActions(onExportConfig = { picked = true }))
        rule.onNodeWithTag(TAG_SETTINGS_LIST).performScrollToNode(hasTestTag(TAG_CFG_EXPORT_ROW))
        rule.onNodeWithTag(TAG_CFG_EXPORT_ROW).performClick()
        assertFalse("the row alone must not open the picker", picked)
        rule.onNodeWithTag(TAG_CFG_PRIVATE).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Keep it private", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag(TAG_CFG_SAVE).performClick()
        assertTrue(picked)
    }

    @Test
    fun import_row_opens_the_picker() {
        var opened = false
        show(actions = SettingsActions(onImportConfig = { opened = true }))
        rule.onNodeWithTag(TAG_SETTINGS_LIST).performScrollToNode(hasTestTag(TAG_CFG_IMPORT_ROW))
        rule.onNodeWithTag(TAG_CFG_IMPORT_ROW).performClick()
        assertTrue(opened)
    }

    @Test
    fun the_import_question_says_what_changes_and_only_import_applies_it() {
        var imported = 0
        var cancelled = 0
        val plan = ConfigTransfer.Plan(
            newRules = listOf(
                ConfigTransfer.RuleEntry(com.privacyguard.core.filter.FilterRule.Action.DENY, site = "a.example"),
                ConfigTransfer.RuleEntry(com.privacyguard.core.filter.FilterRule.Action.DENY, site = "b.example"),
                ConfigTransfer.RuleEntry(com.privacyguard.core.filter.FilterRule.Action.ALLOW, site = "c.example"),
            ),
            alreadyThere = 1,
        )
        showImport(ImportUi.Confirm(plan, settingsChanged = 4, skipped = 2),
            SettingsActions(onConfirmImport = { imported++ }, onCancelImport = { cancelled++ }))
        rule.onNodeWithTag(TAG_CFG_CONFIRM).assertIsDisplayed()
        listOf("3 new rules", "2 block · 1 allow", "4 settings will change", "1 rule is already on this phone",
            "2 entries are not valid and are left out").forEach { rule.onNodeWithText(it).assertExists() }
        assertTrue(rule.onAllNodesWithText("never lets a malware", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals("nothing applied before the answer", 0, imported)
        rule.onNodeWithTag(TAG_CFG_IMPORT).performClick()
        assertEquals(1, imported)
        assertEquals(0, cancelled)
    }

    @Test
    fun a_refused_file_says_why() {
        showImport(ImportUi.Problem(com.privacyguard.app.R.string.ui_cfg_err_newer))
        rule.onNodeWithText("Can't import this file").assertExists()
        rule.onNodeWithText("This file was made by a newer version of CoreLock. Update the app first.").assertExists()
    }
}
