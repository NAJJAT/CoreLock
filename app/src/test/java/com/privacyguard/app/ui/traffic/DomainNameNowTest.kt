package com.privacyguard.app.ui.traffic

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.privacyguard.app.ui.activity.ActivityAction
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

/** Domain detail's Allow and Block (Phase 5): the same choices as Activity's sheet. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DomainNameNowTest {

    @get:Rule
    val rule = createComposeRule()

    private fun item(reason: ActivityReason, pkg: String = "", lookup: Boolean = pkg.isEmpty(), ruleId: String? = null) = ActivityItem(
        id = "n", isLookup = lookup, timestamp = 0, packageName = pkg, appName = if (pkg.isEmpty()) "" else "Chrome",
        host = "ads.example.com", ip = null, port = null, protocol = null,
        blocked = reason != ActivityReason.ALLOWED, blockSource = if (reason != ActivityReason.ALLOWED) BlockSource.DNS else null,
        reason = reason, ruleId = ruleId,
    )

    private fun show(item: ActivityItem, onAction: (ActivityAction) -> Unit = {}) = rule.setContent {
        CoreLockTheme(Appearance.DARK) { NameNow(item, onAction) }
    }

    @Test
    fun a_listed_name_without_an_app_can_be_allowed_everywhere_only() {
        var done: ActivityAction? = null
        show(item(ActivityReason.ADS_LIST)) { done = it }
        rule.onNodeWithText("Blocked · Ads and trackers list").assertIsDisplayed()
        rule.onNodeWithText("This was blocked before the app connected", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("action_ALLOW_FOR_APP").assertDoesNotExist()
        rule.onNodeWithTag("action_KEEP_BLOCKED").assertDoesNotExist()
        rule.onNodeWithTag("action_ALLOW_EVERYWHERE").performClick()
        assertEquals(ActivityAction.ALLOW_EVERYWHERE, done)
    }

    @Test
    fun from_an_apps_traffic_a_connection_block_can_be_allowed_for_that_app() {
        show(item(ActivityReason.ADS_LIST, pkg = "com.android.chrome", lookup = false))
        rule.onNodeWithTag("action_ALLOW_FOR_APP").assertIsDisplayed()
        rule.onNodeWithTag("action_ALLOW_EVERYWHERE").assertIsDisplayed()
    }

    @Test
    fun an_allowed_name_can_be_blocked() {
        show(item(ActivityReason.ALLOWED))
        rule.onNodeWithText("Allowed").assertIsDisplayed()
        rule.onNodeWithTag("action_BLOCK_EVERYWHERE").assertIsDisplayed()
    }

    @Test
    fun the_users_own_block_can_be_removed() {
        show(item(ActivityReason.USER_RULE, ruleId = "r1"))
        rule.onNodeWithTag("action_REMOVE_MY_RULE").assertIsDisplayed()
    }

    @Test
    fun a_malware_name_only_offers_allow_anyway_which_warns_first() {
        show(item(ActivityReason.MALWARE_LIST))
        rule.onNodeWithText("Blocked · Malware list").assertIsDisplayed()
        rule.onNodeWithTag("action_ALLOW_ANYWAY").assertIsDisplayed()
        rule.onNodeWithTag("action_ALLOW_EVERYWHERE").assertDoesNotExist()
    }
}
