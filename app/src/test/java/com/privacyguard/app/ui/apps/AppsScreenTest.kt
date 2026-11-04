package com.privacyguard.app.ui.apps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.privacyguard.app.ui.activity.ActivityItem
import com.privacyguard.app.ui.activity.ActivityReason
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import com.privacyguard.core.session.BlockSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** DESIGN_SPEC_1.1.md §6.3, §6.4 and §9 Phase 5: Apps and App detail. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(name: String = "Chrome", pkg: String = "com.android.chrome", stalker: Int = 0, status: AppStatus = AppStatus.ALLOWED, blocked: Int = 0) =
        AppRiskItem(
            appName = name, packageName = pkg, totalDestinations = 4, suspiciousCount = 0, cleartextCount = 0,
            totalBytesOut = 1000, maxRiskScore = 12, status = status, stalkerwareScore = stalker,
            stalkerwareReasons = if (stalker > 0) listOf("reads your location") else emptyList(), blockedToday = blocked,
        )

    private fun showList(state: AppsUiState, actions: AppsActions = AppsActions()) = rule.setContent {
        CoreLockTheme(Appearance.DARK) { AppsContent(state, actions) }
    }

    // ── Apps list ───────────────────────────────────────────────────────────

    @Test
    fun the_row_shows_status_blocks_and_word_but_no_package_name() {
        showList(AppsUiState(AppsList.Loaded(listOf(app(blocked = 3)))))
        rule.onNodeWithText("Chrome").assertIsDisplayed()
        rule.onNodeWithText("Allowed · 3 blocked today").assertIsDisplayed()
        rule.onNodeWithText("Looks normal").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("com.android.chrome", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun tapping_the_risk_word_in_app_detail_explains_it() {
        showDetail(detail.copy(summary = app(stalker = 80)))
        rule.onNodeWithTag(TAG_RISK_WORD).performClick()
        rule.onNodeWithText("reads your location", substring = true).assertIsDisplayed()
    }

    @Test
    fun the_whole_row_opens_the_app_even_at_its_centre() {
        var opened = false
        showList(AppsUiState(AppsList.Loaded(listOf(app(stalker = 80)))), AppsActions(onOpen = { opened = true }))
        rule.onNodeWithTag("${TAG_APP_ROW}com.android.chrome").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun could_watch_banner_shows_them_first() {
        var sort: AppSort? = null
        showList(AppsUiState(AppsList.Loaded(listOf(app(stalker = 80), app("Maps", "com.maps")))), AppsActions(onSort = { sort = it }))
        rule.onNodeWithText("1 app could be watching you", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Show them").performClick()
        assertEquals(AppSort.RISK, sort)
    }

    @Test
    fun empty_states_say_why() {
        showList(AppsUiState(AppsList.Loaded(listOf(app())), query = "zzz"))
        rule.onNodeWithText("No app called “zzz”.").assertIsDisplayed()
    }

    @Test
    fun empty_blocked_tells_how_to_block() {
        showList(AppsUiState(AppsList.Loaded(listOf(app())), filter = AppsFilter.BLOCKED))
        rule.onNodeWithText("No apps blocked. Open an app to block it.").assertIsDisplayed()
    }

    @Test
    fun error_offers_try_again() {
        var retried = false
        showList(AppsUiState(AppsList.Error), AppsActions(onRetry = { retried = true }))
        rule.onNodeWithText("Try again").performClick()
        assertEquals(true, retried)
    }

    @Test
    fun loading_is_not_an_empty_list() {
        showList(AppsUiState(AppsList.Loading))
        assertEquals(0, rule.onAllNodesWithText("No apps", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun opening_an_app_is_one_tap() {
        var opened: String? = null
        showList(AppsUiState(AppsList.Loaded(listOf(app()))), AppsActions(onOpen = { opened = it.packageName }))
        rule.onNodeWithText("Chrome").performClick()
        assertEquals("com.android.chrome", opened)
    }

    // ── App detail ──────────────────────────────────────────────────────────

    private fun showDetail(state: AppDetailState, usageAccess: Boolean = true, actions: AppDetailActions = AppDetailActions()) = rule.setContent {
        CoreLockTheme(Appearance.DARK) { AppDetailContent(state, usageAccess, actions) }
    }

    private val detail = AppDetailState(
        packageName = "com.android.chrome", appName = "Chrome", summary = app(blocked = 12), status = AppStatus.ALLOWED,
        connected = ConnectedRecently.Loaded(emptyList()), isScanning = false, isLoadingMismatch = false,
    )

    @Test
    fun block_an_app_in_two_taps() {
        // Tap 1 opened App detail (above); tap 2 is the control.
        var chosen: AppStatus? = null
        showDetail(detail, actions = AppDetailActions(onStatus = { chosen = it }))
        rule.onNodeWithText("Blocked").performClick()
        assertEquals(AppStatus.BLOCKED, chosen)
    }

    @Test
    fun the_header_says_status_and_blocks_today() {
        showDetail(detail)
        rule.onNodeWithText("Allowed · 12 blocked today").assertIsDisplayed()
    }

    @Test
    fun background_only_says_what_it_means_and_asks_for_usage_access() {
        var granted = false
        showDetail(detail.copy(status = AppStatus.BLOCKED_IN_BACKGROUND), usageAccess = false, actions = AppDetailActions(onGrantUsageAccess = { granted = true }))
        rule.onNodeWithText("Can use the internet only while you're using it.", substring = true).assertExists()
        rule.onNodeWithTag(TAG_APP_DETAIL).performScrollToNode(hasText("Open settings"))
        rule.onNodeWithTag(TAG_USAGE_BANNER).assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(true, granted)
    }

    @Test
    fun no_usage_banner_when_access_is_granted() {
        showDetail(detail.copy(status = AppStatus.BLOCKED_IN_BACKGROUND), usageAccess = true)
        rule.onNodeWithTag(TAG_USAGE_BANNER).assertDoesNotExist()
    }

    @Test
    fun technical_details_are_collapsed_and_hold_the_package_and_grade() {
        showDetail(detail)
        rule.onNodeWithText("com.android.chrome").assertDoesNotExist()
        rule.onNodeWithTag(TAG_APP_DETAIL).performScrollToNode(hasTestTag(TAG_TECHNICAL))
        rule.onNodeWithTag(TAG_TECHNICAL).performClick()
        rule.onNodeWithTag(TAG_APP_DETAIL).performScrollToNode(hasText("com.android.chrome"))
        rule.onNodeWithText("com.android.chrome").assertIsDisplayed()
        rule.onNodeWithText("Risk grade").assertExists()
    }

    @Test
    fun a_connected_name_opens_its_sheet() {
        val item = ActivityItem(
            id = "c1", isLookup = false, timestamp = System.currentTimeMillis(), packageName = "com.android.chrome", appName = "Chrome",
            host = "ads.example.com", ip = "203.0.113.1", port = 443, protocol = "TCP", blocked = true, blockSource = BlockSource.SNI,
            reason = ActivityReason.ADS_LIST, title = "Example ads",
        )
        var selected: ActivityItem? = null
        showDetail(
            detail.copy(connected = ConnectedRecently.Loaded(AppDetailLogic.sections(listOf(item)))),
            actions = AppDetailActions(onSelect = { selected = it }),
        )
        rule.onNodeWithTag(TAG_APP_DETAIL).performScrollToNode(hasText("Example ads"))
        rule.onNodeWithTag(TAG_APP_DETAIL).performScrollToNode(hasTestTag("${TAG_NAME_ROW}ads.example.com"))
        rule.onNodeWithTag("${TAG_NAME_ROW}ads.example.com").performClick()
        assertEquals("c1", selected?.id)
    }

    @Test
    fun no_connections_is_said_plainly() {
        showDetail(detail)
        rule.onNodeWithTag(TAG_APP_DETAIL).performScrollToNode(hasText("No connections in the last 24 hours."))
        rule.onNodeWithText("No connections in the last 24 hours.").assertIsDisplayed()
    }
}
