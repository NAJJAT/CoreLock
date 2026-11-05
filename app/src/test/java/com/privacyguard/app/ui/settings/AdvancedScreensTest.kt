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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.privacyguard.app.core.blocklist.BlocklistSource
import com.privacyguard.app.core.power.BatteryCheck
import com.privacyguard.app.ui.theme.Appearance
import com.privacyguard.app.ui.theme.CoreLockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Settings › Advanced and the Settings rows of the parity table that move there (DESIGN_SPEC_1.1.md §5.6, §6.6). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdvancedScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        CoreLockTheme(Appearance.DARK) { content() }
    }

    private fun assertRows(vararg titles: String) = titles.forEach { title ->
        rule.onNodeWithTag(TAG_ADV_LIST).performScrollToNode(hasText(title))
        assertTrue(title, rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun advanced_has_its_four_screens_and_says_who_it_is_for() {
        show { AdvancedScreen(AdvancedNav(onOpenHttpsInspection = {})) }
        rule.onNodeWithText("For experts. Wrong settings can break your connection.").assertIsDisplayed()
        assertRows("Network", "HTTPS inspection", "Analysis", "Diagnostics")
    }

    @Test
    fun without_https_inspection_its_row_is_not_offered() {
        show { AdvancedScreen(AdvancedNav(onOpenHttpsInspection = null)) }
        assertEquals(0, rule.onAllNodes(hasTestTag(TAG_ADV_HTTPS)).fetchSemanticsNodes().size)
    }

    @Test
    fun network_holds_every_1_0_network_setting() {
        // S2, S3, S4, S11, S13, S17, S27.
        show { NetworkContent(NetworkState(), NetworkActions()) }
        assertRows(
            "Encrypted DNS", "DNS server", "Fast web protocol (QUIC)", "Old encryption",
            "Unencrypted connections", "Use two proxy servers", "Public IP check",
        )
    }

    @Test
    fun encrypted_dns_warns_about_its_failure_mode_only_when_on() {
        show { NetworkContent(NetworkState(dohEnabled = false), NetworkActions()) }
        assertEquals(0, rule.onAllNodes(hasTestTag(TAG_NET_DOH_NOTE)).fetchSemanticsNodes().size)
    }

    @Test
    fun encrypted_dns_on_names_the_provider_and_what_happens_if_it_fails() {
        show { NetworkContent(NetworkState(dohEnabled = true, dohProvider = com.privacyguard.app.data.local.preferences.SettingsPreferences.DOH_QUAD9), NetworkActions()) }
        rule.onNodeWithText("Lookups go to Quad9", substring = true).assertIsDisplayed()
    }

    @Test
    fun a_toggle_reports_the_new_value() {
        var quic: Boolean? = null
        show { NetworkContent(NetworkState(blockQuic = false), NetworkActions(onQuic = { quic = it })) }
        rule.onNodeWithTag(TAG_NET_QUIC).performClick()
        assertEquals(true, quic)
    }

    @Test
    fun the_dns_server_shows_the_preset_name_or_the_typed_address() {
        show { NetworkContent(NetworkState(upstreamDns = "192.168.1.1"), NetworkActions()) }
        rule.onNodeWithText("192.168.1.1").assertIsDisplayed()
    }

    @Test
    fun picking_a_known_dns_server_sends_the_preset() {
        var picked: AdvancedLogic.DnsPreset? = null
        show { NetworkContent(NetworkState(), NetworkActions(onDnsPreset = { picked = it })) }
        rule.onNodeWithTag(TAG_NET_DNS).performClick()
        rule.onNodeWithText("Quad9").performClick()
        assertEquals(AdvancedLogic.DnsPreset.QUAD9, picked)
    }

    @Test
    fun a_typed_dns_server_is_saved_only_when_complete_and_by_the_button() {
        var saved: String? = null
        show { NetworkContent(NetworkState(), NetworkActions(onCustomDns = { saved = it })) }
        rule.onNodeWithTag(TAG_NET_DNS).performClick()
        rule.onNodeWithText("Other server").performClick()
        rule.onNodeWithTag(TAG_NET_DNS_FIELD).performTextInput("1.1.1.")
        rule.onNodeWithText("Enter an IP address, like 1.1.1.1 or 2606:4700::1111.").assertIsDisplayed()
        rule.onNodeWithTag(TAG_NET_DNS_USE).performScrollTo().assertIsNotEnabled()
        assertNull("1.0 saved while typing", saved)
        rule.onNodeWithTag(TAG_NET_DNS_FIELD).performTextInput("10")
        rule.onNodeWithTag(TAG_NET_DNS_USE).performScrollTo().assertIsEnabled().performClick()
        assertEquals("1.1.1.10", saved)
    }

    @Test
    fun the_proxy_screen_says_when_a_server_is_missing() {
        show { ProxyChainContent(ProxyState(enabled = true, host1 = "a.example"), ProxyActions()) }
        rule.onNodeWithTag(TAG_PROXY_INCOMPLETE).assertIsDisplayed()
        // S28 lives on this screen.
        assertRows("Block other traffic")
    }

    @Test
    fun clearing_the_port_does_not_save_a_default_and_a_bad_port_is_explained() {
        val ports = mutableListOf<Int>()
        show { ProxyChainContent(ProxyState(enabled = true), ProxyActions(onPort1 = { ports += it })) }
        rule.onNodeWithTag(TAG_ADV_LIST).performScrollToNode(hasTestTag(TAG_PROXY_PORT + "1"))
        rule.onNodeWithTag(TAG_PROXY_PORT + "1").performTextClearance()
        rule.onNodeWithText("Enter a port from 1 to 65535.").assertIsDisplayed()
        rule.onNodeWithTag(TAG_PROXY_PORT + "1").performTextInput("9050")
        assertEquals(9050, ports.last())
        assertTrue("1.0 wrote 1080 while the field was empty: $ports", 1080 !in ports)
    }

    @Test
    fun analysis_reaches_every_1_0_analysis_screen_and_its_settings() {
        // S12, S22, S23, S24, the old "Run report" and the 1.0 Home's security systems.
        show { AnalysisContent(AnalysisNav(), ctEnabled = false, onCt = {}, onReportNow = {}) }
        assertRows(
            "Statistics", "Alerts", "Security systems", "Security analysis", "Ad privacy",
            "Ads and trackers detail", "Certificate checks", "Make a report now",
        )
    }

    @Test
    fun diagnostics_shows_every_check_with_a_word_not_only_a_colour() {
        // S15, S16, S18, S19.
        val state = DiagnosticsState(
            checks = listOf(DiagnosticCheck("Private DNS", DiagnosticStatus.WARN, "Set to a hostname")),
            listTimes = listOf(BlocklistSource.HAGEZI_PRO_MINI to 0L),
            battery = BatteryCheck.State.RESTRICTED,
        )
        show { DiagnosticsContent(state, DiagnosticsActions(), now = 10_000_000L) }
        assertRows("Network diagnostics", "Private DNS", "App integrity", "Blocklist details", "HaGeZi Pro mini", "Not downloaded yet", "Battery", "Open battery settings")
        assertTrue(rule.onAllNodesWithText("Warn").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Fail").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun checks_never_run_say_so_and_the_button_starts_them() {
        var ran = false
        show { DiagnosticsContent(DiagnosticsState(), DiagnosticsActions(onRunChecks = { ran = true })) }
        rule.onNodeWithText("Not checked yet.").assertIsDisplayed()
        rule.onNodeWithTag(TAG_DIAG_RUN).performClick()
        assertTrue(ran)
    }
}
