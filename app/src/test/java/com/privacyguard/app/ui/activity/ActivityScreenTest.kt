package com.privacyguard.app.ui.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.core.session.BlockSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** DESIGN_SPEC_1.1.md §6.2 and §9 Phase 4: Activity's states, the detail sheet, and the fix flow in 3 taps. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val now = System.currentTimeMillis()

    private fun item(
        id: String = "c1",
        host: String = "googleads.g.doubleclick.net",
        title: String = "Google ads",
        lookup: Boolean = false,
        reason: ActivityReason = ActivityReason.ADS_LIST,
        pkg: String = "com.android.chrome",
        time: Long = now - 1_000,
        ruleId: String? = null,
    ) = ActivityItem(
        id = id, isLookup = lookup, timestamp = time, packageName = pkg, appName = "Chrome", host = host,
        ip = if (lookup) null else "142.250.1.1", port = if (lookup) null else 443, protocol = if (lookup) null else "TCP",
        blocked = reason != ActivityReason.ALLOWED, blockSource = if (lookup) BlockSource.DNS else BlockSource.SNI,
        reason = reason, ruleId = ruleId, title = title,
    )

    private fun show(state: ActivityUiState, actions: ActivityActions = ActivityActions()) = rule.setContent {
        CoreLockTheme(Appearance.DARK) { ActivityContent(state, actions) }
    }

    // ── States ───────────────────────────────────────────────────────────────

    @Test
    fun loading_shows_the_skeleton_not_an_empty_message() {
        show(ActivityUiState(list = ActivityList.Loading, protectionOn = true))
        rule.onNodeWithContentDescription("Loading").assertExists()
        rule.onNodeWithText("Nothing blocked yet. CoreLock will show blocked ads and trackers here.").assertDoesNotExist()
    }

    @Test
    fun empty_blocked_list_explains_itself() {
        show(ActivityUiState(list = ActivityList.Loaded(emptyList(), now), protectionOn = true))
        rule.onNodeWithText("Nothing blocked yet. CoreLock will show blocked ads and trackers here.").assertExists()
    }

    @Test
    fun empty_while_off_offers_turn_on() {
        var turnedOn = false
        show(ActivityUiState(list = ActivityList.Loaded(emptyList(), now), protectionOn = false), ActivityActions(onTurnOn = { turnedOn = true }))
        rule.onNodeWithText("Protection is off, so nothing is being blocked.").assertExists()
        rule.onNodeWithText("Turn on").performClick()
        assertTrue(turnedOn)
    }

    @Test
    fun error_offers_try_again() {
        var retried = false
        show(ActivityUiState(list = ActivityList.Error), ActivityActions(onRetry = { retried = true }))
        rule.onNodeWithText("Couldn't load activity.").assertExists()
        rule.onNodeWithText("Try again").performClick()
        assertTrue(retried)
    }

    @Test
    fun rows_show_plain_name_app_and_reason_in_their_group() {
        val rows = listOf(
            item(),
            item(id = "c2", host = "evil.example", title = "evil.example", reason = ActivityReason.MALWARE_LIST, time = now - 2 * 3_600_000L),
        )
        var opened: ActivityItem? = null
        show(ActivityUiState(list = ActivityList.Loaded(rows, now), protectionOn = true), ActivityActions(onSelect = { opened = it }))
        rule.onNodeWithText("Just now").assertExists()
        rule.onNodeWithText("Google ads").assertExists()
        rule.onNodeWithText("Blocked · Ads and trackers list").assertExists()
        rule.onNodeWithText("Blocked · Malware list").performScrollTo().assertExists()
        rule.onNodeWithText("Google ads").performClick()
        assertEquals("c1", opened?.id)
    }

    @Test
    fun the_two_lookup_service_blocks_read_differently() {
        // One can be allowed with a rule, the other (port 853) never: identical rows hid that.
        val rows = listOf(
            item(id = "a", host = "dns.google", title = "Google services", lookup = false, reason = ActivityReason.LOOKUP_BYPASS),
            item(id = "b", host = "dns.google", title = "Google services", lookup = false, reason = ActivityReason.LOOKUP_BYPASS_PORT, time = now - 2_000),
        )
        show(ActivityUiState(list = ActivityList.Loaded(rows, now), protectionOn = true))
        rule.onNodeWithText("Blocked · Skips CoreLock").assertExists()
        rule.onNodeWithText("Blocked · Never allowed").assertExists()
    }

    @Test
    fun blocked_is_the_default_and_all_is_one_tap() {
        var chosen: ActivityFilter? = null
        show(ActivityUiState(list = ActivityList.Loaded(emptyList(), now), protectionOn = true), ActivityActions(onFilter = { chosen = it }))
        rule.onNodeWithText("Blocked").assertIsSelected()
        rule.onNodeWithText("All").performClick()
        assertEquals(ActivityFilter.ALL, chosen)
    }

    @Test
    fun something_not_working_opens_the_fix_flow() {
        var opened = false
        show(ActivityUiState(list = ActivityList.Loaded(emptyList(), now), protectionOn = true), ActivityActions(onOpenFix = { opened = true }))
        rule.onNodeWithTag(TAG_FIX_BUTTON).performClick()
        assertTrue(opened)
    }

    // ── Detail sheet ─────────────────────────────────────────────────────────

    private fun detail(item: ActivityItem, onAction: (ActivityAction) -> Unit = {}) = rule.setContent {
        CoreLockTheme(Appearance.DARK) { Column { ActivityDetail(item, onAction, onOpenDomain = {}, onOpenApp = {}) } }
    }

    @Test
    fun detail_says_what_and_why_and_allows_for_this_app() {
        var action: ActivityAction? = null
        detail(item()) { action = it }
        rule.onNodeWithText("Google ads (googleads.g.doubleclick.net) from Chrome").assertExists()
        rule.onNodeWithText("It's on the Ads and trackers list.").assertExists()
        rule.onNodeWithText("Allow for this app").performClick()
        assertEquals(ActivityAction.ALLOW_FOR_APP, action)
    }

    @Test
    fun dns_layer_detail_offers_everywhere_only_with_the_d3_line() {
        detail(item(lookup = true))
        rule.onNodeWithText("This was blocked before the app connected, so it can only be allowed for all apps.").assertExists()
        rule.onNodeWithText("Allow for this app").assertDoesNotExist()
        rule.onNodeWithText("Allow everywhere").assertExists()
    }

    @Test
    fun malware_detail_has_keep_blocked_first_and_allow_anyway() {
        var action: ActivityAction? = null
        detail(item(reason = ActivityReason.MALWARE_LIST)) { action = it }
        rule.onNodeWithText("It's on the malware list. Sites like this can steal passwords or install harmful apps.").assertExists()
        rule.onNodeWithText("Allow everywhere").assertDoesNotExist()
        rule.onNodeWithText("Allow anyway").performClick()
        assertEquals(ActivityAction.ALLOW_ANYWAY, action)
    }

    @Test
    fun own_rule_detail_offers_remove_my_rule() {
        var action: ActivityAction? = null
        detail(item(reason = ActivityReason.USER_RULE, ruleId = "r1")) { action = it }
        rule.onNodeWithText("Remove my rule").performClick()
        assertEquals(ActivityAction.REMOVE_MY_RULE, action)
    }

    @Test
    fun technical_details_are_collapsed_and_hold_the_layer() {
        detail(item())
        rule.onNodeWithText("Stopped by").assertDoesNotExist()
        rule.onNodeWithText("Technical details").performClick()
        rule.onNodeWithText("Stopped by").assertExists()
        rule.onNodeWithText("142.250.1.1").assertExists()
    }

    // ── Fix flow: 3 taps, no typing ──────────────────────────────────────────

    @Test
    fun fix_a_broken_site_in_three_taps() {
        val rows = listOf(item(), item(id = "c2", host = "cdn.example", title = "cdn.example", time = now - 2_000))
        var step by mutableStateOf<FixStep>(FixStep.App)
        var taps = 0
        var done: Pair<ActivityAction, ActivityItem>? = null
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                FixFlowContent(
                    state = FixUiState(step, FixData.Loaded(rows)),
                    actions = FixFlowActions(
                        onChooseApp = { pkg, name -> taps++; step = FixStep.Names(pkg, name) },
                        onChooseName = { n -> taps++; step = FixStep.Decide(n, step as FixStep.Names) },
                        onAction = { a, i -> taps++; done = a to i },
                    ),
                )
            }
        }
        rule.onNodeWithText("Which app isn't working?").assertExists()
        rule.onNodeWithText("Chrome").performClick()
        rule.onNodeWithText("This was blocked in the last hour").assertExists()
        rule.onNodeWithText("cdn.example").performClick()
        rule.onNodeWithText("Allow for this app").performScrollTo().performClick()
        assertEquals(3, taps)
        assertEquals(ActivityAction.ALLOW_FOR_APP, done?.first)
        assertEquals("cdn.example", done?.second?.host)
    }

    @Test
    fun fix_flow_not_sure_lists_everything_and_empty_hour_says_so() {
        var chosen: Pair<String?, String?>? = null
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                FixFlowContent(FixUiState(FixStep.App, FixData.Loaded(emptyList())),
                    FixFlowActions(onChooseApp = { p, n -> chosen = p to n }))
            }
        }
        rule.onNodeWithTag(TAG_FIX_NOT_SURE).performClick()
        assertEquals(null to null, chosen)
    }

    @Test
    fun after_fixing_every_name_step_two_says_done_not_probably_not_corelock() {
        val rows = listOf(item(host = "cdn.example", title = "cdn.example"))
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                FixFlowContent(FixUiState(FixStep.Names("com.android.chrome", "Chrome"), FixData.Loaded(rows), handled = setOf("cdn.example")), FixFlowActions())
            }
        }
        rule.onNodeWithText("Done. Try the app again. If it still doesn't work, go back and pick another app.").assertExists()
        rule.onNodeWithText("cdn.example").assertDoesNotExist()
    }

    @Test
    fun pause_is_offered_only_when_it_will_end_by_itself() {
        var paused = false
        var asked = false
        var pause by mutableStateOf(com.privacyguard.app.vpn.ProtectionPause.Availability.AVAILABLE)
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                FixFlowContent(FixUiState(FixStep.App, FixData.Loaded(emptyList()), pause = pause), FixFlowActions(onPause = { paused = true }, onAllowAlarms = { asked = true }))
            }
        }
        rule.onNodeWithTag(TAG_FIX_PAUSE).performScrollTo().performClick()
        assertTrue(paused)
        pause = com.privacyguard.app.vpn.ProtectionPause.Availability.NEEDS_EXACT_ALARMS
        rule.onNodeWithTag(TAG_FIX_PAUSE).assertDoesNotExist()
        rule.onNodeWithText("Open settings").performScrollTo().performClick()
        assertTrue(asked)
        pause = com.privacyguard.app.vpn.ProtectionPause.Availability.ALWAYS_ON
        rule.onNodeWithText("Pausing is not available while Always-on VPN is on: Android would turn CoreLock back on at once.").assertExists()
        rule.onNodeWithText("Open settings").assertDoesNotExist()
        pause = com.privacyguard.app.vpn.ProtectionPause.Availability.NOT_RUNNING
        rule.onNodeWithTag(TAG_FIX_PAUSE).assertDoesNotExist()
    }

    @Test
    fun fix_flow_names_empty_says_corelock_is_probably_not_the_cause() {
        rule.setContent {
            CoreLockTheme(Appearance.DARK) {
                FixFlowContent(FixUiState(FixStep.Names(null, null), FixData.Loaded(emptyList())), FixFlowActions())
            }
        }
        rule.onNodeWithText("Nothing was blocked in the last hour, so CoreLock is probably not the cause.").assertExists()
    }
}
