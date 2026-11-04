package com.privacyguard.app.ui.activity

import com.privacyguard.app.core.tracker.DomainPurpose
import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import com.privacyguard.core.session.BlockSource
import com.privacyguard.vpn.firewall.DomainFilter
import com.privacyguard.vpn.forwarder.DnsBlockDecision.Reason
import com.privacyguard.vpn.forwarder.NameBlockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** DESIGN_SPEC_1.1.md §6.2 and §9 Phase 4: reasons, groups, actions (D3) and the fix flow's order. */
class ActivityLogicTest {

    private val zone = TimeZone.getTimeZone("Europe/Amsterdam")
    // 2026-11-03 14:00 in Amsterdam (UTC+1).
    private val now = 1_793_710_800_000L

    private fun item(
        host: String? = "ads.example.com",
        pkg: String = "com.android.chrome",
        lookup: Boolean = false,
        reason: ActivityReason = ActivityReason.ADS_LIST,
        blocked: Boolean = reason != ActivityReason.ALLOWED,
        time: Long = now,
        ruleId: String? = null,
        purpose: DomainPurpose? = null,
    ) = ActivityItem(
        id = "x$host$time$pkg", isLookup = lookup, timestamp = time, packageName = pkg, appName = "Chrome",
        host = host, ip = "203.0.113.7", port = 443, protocol = "TCP", blocked = blocked,
        blockSource = if (lookup) BlockSource.DNS else BlockSource.SNI, reason = reason, ruleId = ruleId, purpose = purpose,
    )

    // ── Groups ───────────────────────────────────────────────────────────────

    @Test
    fun groups_are_just_now_today_yesterday_earlier() {
        assertEquals(ActivityGroup.JUST_NOW, ActivityLogic.group(now - 4 * 60_000, now, zone))
        assertEquals(ActivityGroup.TODAY, ActivityLogic.group(now - 6 * 60_000, now, zone))
        // 00:30 today is today; 23:30 the day before is yesterday.
        assertEquals(ActivityGroup.TODAY, ActivityLogic.group(now - (13 * 60 + 30) * 60_000L, now, zone))
        assertEquals(ActivityGroup.YESTERDAY, ActivityLogic.group(now - (14 * 60 + 30) * 60_000L, now, zone))
        assertEquals(ActivityGroup.EARLIER, ActivityLogic.group(now - 40 * 3_600_000L, now, zone))
    }

    @Test
    fun grouped_keeps_group_order_and_newest_first() {
        val rows = listOf(item(time = now - 3_600_000), item(host = "a.example", time = now - 1_000), item(time = now - 50 * 3_600_000L), item(host = "b.example", time = now - 2_000))
        val groups = ActivityLogic.grouped(rows, now, zone)
        assertEquals(listOf(ActivityGroup.JUST_NOW, ActivityGroup.TODAY, ActivityGroup.EARLIER), groups.map { it.first })
        assertEquals(listOf(now - 1_000, now - 2_000), groups[0].second.map { it.timestamp })
    }

    @Test
    fun repeats_in_a_group_become_one_row_with_a_count() {
        val rows = listOf(
            item(time = now - 1_000), item(time = now - 3_000), item(time = now - 2_000),
            item(pkg = "com.other", time = now - 1_500),
            item(reason = ActivityReason.MALWARE_LIST, time = now - 2_500),
            item(time = now - 3 * 3_600_000L),
        )
        val groups = ActivityLogic.grouped(rows, now, zone)
        val justNow = groups.single { it.first == ActivityGroup.JUST_NOW }.second
        assertEquals(3, justNow.size)
        val chrome = justNow.single { it.packageName == "com.android.chrome" && it.reason == ActivityReason.ADS_LIST }
        assertEquals(3, chrome.count)
        assertEquals("the newest of the repeats is shown", now - 1_000, chrome.timestamp)
        // Another app or another reason is a different row; another group is never merged in.
        assertEquals(1, groups.single { it.first == ActivityGroup.TODAY }.second.single().count)
    }

    @Test
    fun ip_only_rows_merge_by_what_they_show_but_names_never_do() {
        val a = item(host = null, reason = ActivityReason.LOOKUP_BYPASS, time = now - 1_000).copy(ip = "8.8.8.8", title = "Google services")
        val b = item(host = null, reason = ActivityReason.LOOKUP_BYPASS, time = now - 2_000).copy(ip = "8.8.4.4", title = "Google services")
        val c = item(host = "ads1.example", time = now - 1_000).copy(title = "Example ads")
        val d = item(host = "ads2.example", time = now - 2_000).copy(title = "Example ads")
        val rows = ActivityLogic.merged(listOf(a, b, c, d))
        assertEquals(2, rows.single { it.host == null }.count)
        assertEquals("an Allow acts on one name, so two names stay two rows", 2, rows.count { it.host != null })
    }

    // ── Reasons (order of NameBlockPolicy) ───────────────────────────────────

    private val userBlock = FilterRule("r1", "Block ads", FilterRule.Action.DENY, FilterRule.Source.USER, matchDomain = "ads.example.com")

    @Test
    fun a_name_on_no_list_is_never_shown_as_listed() {
        // Blocked by a rule deleted since: lookups store no source, so only a list that
        // still has the name may be named (found on the emulator: "iana.org · Ads list").
        assertEquals(ActivityReason.SETTING, ActivityLogic.reasonFor(true, true, BlockSource.DNS, null, false, onBlockList = false))
        assertEquals(ActivityReason.SETTING, ActivityLogic.reasonFor(true, false, BlockSource.SNI, null, false, onBlockList = false))
        assertEquals(ActivityReason.ADS_LIST, ActivityLogic.reasonFor(true, true, BlockSource.DNS, null, false, onBlockList = true))
        // Malware and the user's rule keep their order.
        assertEquals(ActivityReason.MALWARE_LIST, ActivityLogic.reasonFor(true, true, BlockSource.DNS, null, true, onBlockList = false))
        assertEquals(ActivityReason.USER_RULE, ActivityLogic.reasonFor(true, true, BlockSource.DNS, userBlock, false, onBlockList = false))
    }

    @Test
    fun reason_follows_the_decision_order() {
        assertEquals(ActivityReason.ALLOWED, ActivityLogic.reasonFor(false, false, null, userBlock, true))
        // The user's Block first, even for a name on a malware list.
        assertEquals(ActivityReason.USER_RULE, ActivityLogic.reasonFor(true, true, BlockSource.DNS, userBlock, true))
        assertEquals(ActivityReason.MALWARE_LIST, ActivityLogic.reasonFor(true, true, BlockSource.DNS, null, true))
        assertEquals(ActivityReason.ADS_LIST, ActivityLogic.reasonFor(true, true, BlockSource.DNS, null, false))
        assertEquals(ActivityReason.ADS_LIST, ActivityLogic.reasonFor(true, false, BlockSource.QUIC, null, false))
        assertEquals(ActivityReason.LOOKUP_BYPASS, ActivityLogic.reasonFor(true, false, BlockSource.DOH, null, false))
        assertEquals(ActivityReason.LOOKUP_BYPASS_PORT, ActivityLogic.reasonFor(true, false, BlockSource.DOT, null, false))
        // No source and no rule: never claim a list we can't name.
        assertEquals(ActivityReason.SETTING, ActivityLogic.reasonFor(true, false, null, null, false))
        // Plain lookups past CoreLock's resolver (port 53 over TCP or IPv6) are closed before
        // any rule, so no rule is named, and no list either.
        assertEquals(ActivityReason.LOOKUP_BYPASS_PORT, ActivityLogic.reasonFor(true, false, null, userBlock, false, port = 53))
        assertEquals(ActivityReason.LOOKUP_BYPASS_PORT, ActivityLogic.reasonFor(true, false, BlockSource.DOT, userBlock, false, port = 853))
        assertEquals(ActivityReason.SETTING, ActivityLogic.reasonFor(true, false, null, null, false, port = 80))
    }

    @Test
    fun an_allow_rule_or_a_list_rule_is_not_called_your_rule() {
        val allow = userBlock.copy(action = FilterRule.Action.ALLOW)
        val listRule = userBlock.copy(source = FilterRule.Source.BLOCKLIST)
        assertEquals(ActivityReason.ADS_LIST, ActivityLogic.reasonFor(true, false, BlockSource.SNI, allow, false))
        assertEquals(ActivityReason.ADS_LIST, ActivityLogic.reasonFor(true, false, BlockSource.SNI, listRule, false))
    }

    // ── Actions (§6.2, D3) ────────────────────────────────────────────────────

    @Test
    fun connection_layer_ad_block_offers_app_everywhere_and_keep() {
        assertEquals(
            listOf(ActivityAction.ALLOW_FOR_APP, ActivityAction.ALLOW_EVERYWHERE, ActivityAction.KEEP_BLOCKED),
            ActivityLogic.actionsFor(item()),
        )
    }

    @Test
    fun dns_layer_block_offers_everywhere_only_d3() {
        val lookup = item(lookup = true)
        assertTrue(ActivityLogic.everywhereOnly(lookup))
        assertEquals(listOf(ActivityAction.ALLOW_EVERYWHERE, ActivityAction.KEEP_BLOCKED), ActivityLogic.actionsFor(lookup))
        // A connection whose app Android didn't name is the same: no app to allow it for.
        assertEquals(listOf(ActivityAction.ALLOW_EVERYWHERE, ActivityAction.KEEP_BLOCKED), ActivityLogic.actionsFor(item(pkg = "")))
    }

    @Test
    fun malware_keeps_blocked_first_and_allow_anyway_second() {
        assertEquals(
            listOf(ActivityAction.KEEP_BLOCKED, ActivityAction.ALLOW_ANYWAY),
            ActivityLogic.actionsFor(item(reason = ActivityReason.MALWARE_LIST)),
        )
    }

    @Test
    fun own_rule_offers_remove_my_rule() {
        assertEquals(
            listOf(ActivityAction.REMOVE_MY_RULE, ActivityAction.KEEP_BLOCKED),
            ActivityLogic.actionsFor(item(reason = ActivityReason.USER_RULE, ruleId = "r1")),
        )
    }

    @Test
    fun allowed_row_offers_block_for_app_and_everywhere() {
        assertEquals(
            listOf(ActivityAction.BLOCK_FOR_APP, ActivityAction.BLOCK_EVERYWHERE),
            ActivityLogic.actionsFor(item(reason = ActivityReason.ALLOWED)),
        )
        assertEquals(listOf(ActivityAction.BLOCK_EVERYWHERE), ActivityLogic.actionsFor(item(reason = ActivityReason.ALLOWED, pkg = "")))
    }

    @Test
    fun lookup_bypass_and_settings_have_no_one_tap_allow() {
        for (r in listOf(ActivityReason.LOOKUP_BYPASS, ActivityReason.LOOKUP_BYPASS_PORT, ActivityReason.SETTING)) {
            assertEquals(r.name, listOf(ActivityAction.KEEP_BLOCKED), ActivityLogic.actionsFor(item(reason = r)))
        }
    }

    // ── Fix flow order ───────────────────────────────────────────────────────

    @Test
    fun fix_apps_are_most_recent_first() {
        val rows = listOf(
            item(pkg = "a", time = now - 10_000), item(pkg = "b", time = now - 1_000),
            item(pkg = "a", time = now - 20_000), item(pkg = "", time = now),
        )
        val apps = ActivityLogic.fixApps(rows)
        assertEquals(listOf("b", "a"), apps.map { it.packageName })
        assertEquals(2, apps[1].blocked)
    }

    @Test
    fun step_two_leaves_out_handled_names_until_undone() {
        val rows = listOf(item(host = "cdn.example"), item(host = "api.example", time = now - 1_000))
        val step = FixStep.Names("com.android.chrome", "Chrome")
        assertEquals(listOf("api.example"), fixStepNames(step, rows, setOf("cdn.example")).first.map { it.item.host })
        assertEquals(2, fixStepNames(step, rows, emptySet()).first.size)
    }

    @Test
    fun a_name_several_apps_hit_is_never_one_apps_to_allow() {
        // Review finding: with "Not sure", app A's row stood for app B's too.
        val rows = listOf(item(host = "x.example", pkg = "a", time = now - 1_000), item(host = "x.example", pkg = "b", time = now - 2_000))
        val name = ActivityLogic.fixNames(rows, null).single()
        assertTrue(ActivityLogic.everywhereOnly(name.item))
        assertEquals(2, name.attempts)
        // One app, connections only: per app is fine.
        assertFalse(ActivityLogic.everywhereOnly(ActivityLogic.fixNames(rows, "a").single().item))
    }

    @Test
    fun a_name_also_blocked_as_a_lookup_is_offered_everywhere() {
        // A per-app Allow acts on connections only; the lookup would stay blocked.
        val rows = listOf(
            item(host = "cdn.x.com", pkg = "com.android.chrome", time = now - 1_000),
            item(host = "cdn.x.com", pkg = "", lookup = true, time = now - 2_000),
        )
        val (own, unattributed) = fixStepNames(FixStep.Names("com.android.chrome", "Chrome"), rows, emptySet())
        assertTrue(ActivityLogic.everywhereOnly(own.single().item))
        assertEquals("Chrome", own.single().item.appName)
        assertEquals(2, own.single().attempts)
        assertTrue("not listed twice", unattributed.isEmpty())
    }

    @Test
    fun the_old_filters_rows_never_show_under_the_new_one() {
        val blocked = ActivityList.Loaded(listOf(item()), now, ActivityFilter.BLOCKED)
        assertEquals(ActivityList.Loading, ActivityViewModel.shownList(ActivityFilter.ALL, blocked, now))
        assertEquals(blocked, ActivityViewModel.shownList(ActivityFilter.BLOCKED, blocked, now - 5))
        assertEquals(now + 60_000, (ActivityViewModel.shownList(ActivityFilter.BLOCKED, blocked, now + 60_000) as ActivityList.Loaded).now)
    }

    @Test
    fun company_names_read_as_people_say_them() {
        assertEquals("Google", ActivityLogic.plainCompany("Google LLC"))
        assertEquals("Meta Platforms", ActivityLogic.plainCompany("Meta Platforms, Inc."))
        assertEquals("Criteo", ActivityLogic.plainCompany("Criteo S.A."))
        assertEquals("Adjust", ActivityLogic.plainCompany("Adjust GmbH"))
        // Only a legal form at the end; never the whole name, never part of a word.
        assertEquals("Inc", ActivityLogic.plainCompany("Inc"))
        assertEquals("Incline", ActivityLogic.plainCompany("Incline"))
        assertEquals("Yandex", ActivityLogic.plainCompany("Yandex"))
    }

    @Test
    fun an_app_is_offered_only_if_step_two_has_a_name_for_it() {
        // Emulator bug: "Android System, 10 attempts" in step 1, then "nothing blocked" in step 2.
        val rows = listOf(
            item(host = null, pkg = "android", reason = ActivityReason.LOOKUP_BYPASS_PORT, time = now - 1_000),
            item(host = "cdn.example", pkg = "b", time = now - 2_000),
            item(host = null, pkg = "b", time = now - 3_000),
        )
        val apps = ActivityLogic.fixApps(rows)
        assertEquals(listOf("b"), apps.map { it.packageName })
        assertEquals("the count matches the names in step 2", ActivityLogic.fixNames(rows, "b").sumOf { it.attempts }, apps.single().blocked)
    }

    @Test
    fun fix_names_put_likely_breakage_first_then_attempts_then_time() {
        val rows = listOf(
            item(host = "ads.example", purpose = DomainPurpose.ADS, time = now),
            item(host = "ads.example", purpose = DomainPurpose.ADS, time = now - 1),
            item(host = "ads.example", purpose = DomainPurpose.ADS, time = now - 2),
            item(host = "cdn.example", purpose = DomainPurpose.CDN, time = now - 5_000),
            item(host = "api.example", purpose = DomainPurpose.FIRST_PARTY, time = now - 4_000),
            item(host = "api.example", purpose = DomainPurpose.FIRST_PARTY, time = now - 4_500),
        )
        val names = ActivityLogic.fixNames(rows, "com.android.chrome")
        assertEquals(listOf("api.example", "cdn.example", "ads.example"), names.map { it.item.host })
        assertEquals(listOf(2, 1, 3), names.map { it.attempts })
    }

    @Test
    fun fix_name_is_per_app_only_when_every_attempt_was_a_connection_of_that_app() {
        // Also blocked as a lookup: per app would leave the lookup blocked (review finding;
        // this test asserted the opposite before).
        val rows = listOf(item(host = "x.example", lookup = true, time = now), item(host = "x.example", time = now - 1_000))
        val name = ActivityLogic.fixNames(rows, null).single()
        assertTrue(ActivityLogic.everywhereOnly(name.item))
        val connectionsOnly = ActivityLogic.fixNames(listOf(item(host = "z.example")), null).single()
        assertFalse(ActivityLogic.everywhereOnly(connectionsOnly.item))
        assertEquals(now, name.lastBlocked)
        val lookupOnly = ActivityLogic.fixNames(listOf(item(host = "y.example", lookup = true)), null).single()
        assertTrue(ActivityLogic.everywhereOnly(lookupOnly.item))
    }

    // ── D8: rules created here, checked through NameBlockPolicy ─────────────

    private fun lists(vararg names: String) = DomainFilter().apply { rebuild(names.toList()) }

    @Test
    fun allow_for_this_app_opens_the_name_for_that_app_only() {
        val rule = ActivityRules.allowForApp("Ads.Example.com.", "com.android.chrome", "Allow")
        assertEquals(FilterRule.HIGH_PRIORITY, rule.priority)
        assertEquals(FilterRule.Source.USER, rule.source)
        val policy = NameBlockPolicy(FilterEngine().apply { addRule(rule) }, domainFilter = lists("ads.example.com"))
        assertNull(policy.reasonFor("ads.example.com", "com.android.chrome"))
        assertEquals(Reason.BLOCKLIST, policy.reasonFor("ads.example.com", "com.other.app"))
    }

    @Test
    fun allow_everywhere_opens_ad_lists_but_not_malware_without_the_warning() {
        val plain = ActivityRules.allowEverywhere("bad.example", "Allow")
        assertEquals(FilterRule.HIGH_PRIORITY, plain.priority)
        val engine = FilterEngine().apply { addRule(plain) }
        val withLists = NameBlockPolicy(engine, domainFilter = lists("bad.example"), securityFilter = lists("bad.example"))
        assertEquals(Reason.SECURITY_LIST, withLists.reasonFor("bad.example", null))
        assertNull(NameBlockPolicy(engine, domainFilter = lists("bad.example")).reasonFor("bad.example", null))

        val confirmed = ActivityRules.allowEverywhere("bad.example", "Allow", overridesSecurity = true)
        val after = NameBlockPolicy(FilterEngine().apply { addRule(confirmed) }, domainFilter = lists("bad.example"), securityFilter = lists("bad.example"))
        assertNull(after.reasonFor("bad.example", null))
    }

    @Test
    fun block_rules_use_high_priority_and_match_through_the_policy() {
        val app = ActivityRules.blockForApp("site.example", "203.0.113.7", "com.android.chrome", "Block")!!
        val all = ActivityRules.blockEverywhere("site.example", null, "Block")!!
        assertEquals(FilterRule.HIGH_PRIORITY, app.priority)
        assertEquals(FilterRule.HIGH_PRIORITY, all.priority)
        assertNull("a name rule never also needs the address", app.matchIp)
        val forApp = NameBlockPolicy(FilterEngine().apply { addRule(app) })
        assertEquals(Reason.USER_RULE, forApp.reasonFor("site.example", "com.android.chrome"))
        assertNull(forApp.reasonFor("site.example", "com.other.app"))
        assertEquals(Reason.USER_RULE, NameBlockPolicy(FilterEngine().apply { addRule(all) }).reasonFor("site.example", "com.other.app"))
        // Same id as the 1.0 Traffic Block button, so either screen removes the other's rule.
        assertEquals("user:block:domain:site.example", all.id)
    }

    @Test
    fun ip_only_block_uses_the_address() {
        val rule = ActivityRules.blockEverywhere(null, "203.0.113.7", "Block")!!
        assertEquals("203.0.113.7", rule.matchIp)
        assertNull(ActivityRules.blockEverywhere(null, null, "Block"))
    }
}
