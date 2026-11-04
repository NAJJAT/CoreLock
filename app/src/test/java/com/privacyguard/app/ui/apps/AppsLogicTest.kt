package com.privacyguard.app.ui.apps

import com.privacyguard.core.filter.FilterEngine
import com.privacyguard.core.filter.FilterRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN_SPEC_1.1.md §6.3, §6.3.1 (D9) and §6.4 (D8): the Apps list and an app's status. */
class AppsLogicTest {

    private val pkg = "com.example.app"

    private fun item(
        stalker: Int = 0, risk: Int = 12, cleartext: Int = 0, suspicious: Int = 0, background: Int = 0,
        destinations: Int = 3, name: String = "App", pkg: String = this.pkg,
        blockedToday: Int = 0, bytes: Long = 0, status: AppStatus = AppStatus.ALLOWED, active: Boolean = false,
    ) = AppRiskItem(
        appName = name, packageName = pkg, totalDestinations = destinations, suspiciousCount = suspicious,
        cleartextCount = cleartext, totalBytesOut = bytes, maxRiskScore = risk, status = status,
        stalkerwareScore = stalker, backgroundCount = background, blockedToday = blockedToday, activeNow = active,
    )

    // ── D9: one test per row of the table, plus the order ───────────────────

    @Test fun row1_stalkerware_score_70_could_watch_you() = assertEquals(RiskWord.COULD_WATCH, AppsLogic.riskWord(item(stalker = 70)))
    @Test fun row2_risk_70_high_risk() = assertEquals(RiskWord.HIGH_RISK, AppsLogic.riskWord(item(risk = 70)))
    @Test fun row3_cleartext_sends_unprotected_data() = assertEquals(RiskWord.UNPROTECTED, AppsLogic.riskWord(item(cleartext = 1)))
    @Test fun row4_suspicious_uses_trackers() = assertEquals(RiskWord.TRACKERS, AppsLogic.riskWord(item(suspicious = 1)))
    @Test fun row5_background_active_in_the_background() = assertEquals(RiskWord.BACKGROUND, AppsLogic.riskWord(item(background = 1)))
    @Test fun row6_no_destinations_no_activity() = assertEquals(RiskWord.NO_ACTIVITY, AppsLogic.riskWord(item(destinations = 0)))
    @Test fun row7_otherwise_looks_normal() = assertEquals(RiskWord.NORMAL, AppsLogic.riskWord(item()))

    @Test
    fun the_first_matching_row_wins() {
        val all = item(stalker = 90, risk = 90, cleartext = 5, suspicious = 5, background = 5, destinations = 0)
        assertEquals(RiskWord.COULD_WATCH, AppsLogic.riskWord(all))
        assertEquals(RiskWord.HIGH_RISK, AppsLogic.riskWord(all.copy(stalkerwareScore = 69)))
        assertEquals(RiskWord.UNPROTECTED, AppsLogic.riskWord(all.copy(stalkerwareScore = 69, maxRiskScore = 69)))
        assertEquals(RiskWord.TRACKERS, AppsLogic.riskWord(all.copy(stalkerwareScore = 0, maxRiskScore = 0, cleartextCount = 0)))
        assertEquals(RiskWord.BACKGROUND, AppsLogic.riskWord(all.copy(stalkerwareScore = 0, maxRiskScore = 0, cleartextCount = 0, suspiciousCount = 0)))
    }

    @Test
    fun the_grade_is_1_0s_and_unchanged() {
        assertEquals("A", item().privacyGrade)
        assertEquals("C", item(risk = 100, cleartext = 4).privacyGrade)
    }

    // ── Status from rules ───────────────────────────────────────────────────

    private fun deny(id: String, pkg: String? = this.pkg, domain: String? = null, background: Boolean? = null, enabled: Boolean = true) =
        FilterRule(
            id = id, label = id, action = FilterRule.Action.DENY, source = FilterRule.Source.USER,
            priority = FilterRule.HIGH_PRIORITY, isEnabled = enabled, matchPackage = pkg, matchDomain = domain, matchBackground = background,
        )

    @Test
    fun status_comes_from_app_level_rules_only() {
        assertEquals(AppStatus.ALLOWED, AppsLogic.statusOf(emptyList(), pkg))
        assertEquals(AppStatus.BLOCKED, AppsLogic.statusOf(listOf(deny("a")), pkg))
        assertEquals(AppStatus.BLOCKED_IN_BACKGROUND, AppsLogic.statusOf(listOf(deny("b", background = true)), pkg))
        // A full block wins over background-only.
        assertEquals(AppStatus.BLOCKED, AppsLogic.statusOf(listOf(deny("a"), deny("b", background = true)), pkg))
    }

    @Test
    fun a_blocked_name_for_the_app_does_not_make_the_whole_app_blocked() {
        // 1.0 counted "Block ads.example.com for this app" (D8) as blocking the app.
        assertEquals(AppStatus.ALLOWED, AppsLogic.statusOf(listOf(deny("n", domain = "ads.example.com")), pkg))
    }

    @Test
    fun disabled_rules_and_other_apps_rules_do_not_count() {
        assertEquals(AppStatus.ALLOWED, AppsLogic.statusOf(listOf(deny("a", enabled = false)), pkg))
        assertEquals(AppStatus.ALLOWED, AppsLogic.statusOf(listOf(deny("a", pkg = "com.other")), pkg))
    }

    // ── Changing the status (D8) ────────────────────────────────────────────

    private fun change(target: AppStatus, rules: List<FilterRule>) = AppsLogic.changeTo(target, pkg, rules, "Block App", "Block App (background)")

    private fun applied(rules: List<FilterRule>, c: AppsLogic.StatusChange): List<FilterRule> {
        val removed = c.remove.map { it.id }.toSet()
        val upserted = c.upsert.associateBy { it.id }
        return rules.filterNot { it.id in removed || it.id in upserted } + c.upsert
    }

    @Test
    fun blocking_writes_one_high_priority_user_rule_that_blocks_the_app() {
        val c = change(AppStatus.BLOCKED, emptyList())
        val rule = c.upsert.single()
        assertEquals(FilterRule.HIGH_PRIORITY, rule.priority)
        assertEquals(FilterRule.Source.USER, rule.source)
        assertEquals(AppsLogic.packageBlockRuleId(pkg), rule.id)
        // Through the real engine: every connection of the app is blocked, other apps aren't.
        val engine = FilterEngine().apply { setRules(listOf(rule)) }
        assertEquals(FilterRule.Action.DENY, engine.evaluate(-1, pkg, "example.com", "203.0.113.1", 443, 6).action)
        assertEquals(FilterRule.Action.DENY, engine.evaluate(-1, pkg, "example.com", "203.0.113.1", 443, 6, isBackground = true).action)
        assertTrue(engine.evaluate(-1, "com.other", "example.com", "203.0.113.1", 443, 6).matchedRule == null)
    }

    @Test
    fun background_only_blocks_only_in_the_background() {
        val rule = change(AppStatus.BLOCKED_IN_BACKGROUND, emptyList()).upsert.single()
        assertEquals(true, rule.matchBackground)
        assertEquals(FilterRule.HIGH_PRIORITY, rule.priority)
        val engine = FilterEngine().apply { setRules(listOf(rule)) }
        assertEquals(FilterRule.Action.DENY, engine.evaluate(-1, pkg, "example.com", "203.0.113.1", 443, 6, isBackground = true).action)
        assertNull(engine.evaluate(-1, pkg, "example.com", "203.0.113.1", 443, 6, isBackground = false).matchedRule)
    }

    @Test
    fun allowing_removes_app_rules_and_keeps_name_rules_and_disabled_ones() {
        val name = deny("n", domain = "ads.example.com")
        val off = deny("off", enabled = false)
        val rules = listOf(deny("a"), deny("b", background = true), name, off)
        val after = applied(rules, change(AppStatus.ALLOWED, rules))
        assertEquals(setOf("n", "off"), after.map { it.id }.toSet())
        assertEquals(AppStatus.ALLOWED, AppsLogic.statusOf(after, pkg))
    }

    @Test
    fun each_target_leaves_exactly_that_status() {
        val starts = listOf(
            emptyList(),
            listOf(deny("a")),
            listOf(deny("b", background = true)),
            listOf(deny("a"), deny("b", background = true)),
        )
        for (start in starts) for (target in AppStatus.entries) {
            val after = applied(start, change(target, start))
            assertEquals("from ${start.map { it.id }} to $target", target, AppsLogic.statusOf(after, pkg))
        }
    }

    @Test
    fun between_two_blocked_statuses_the_app_is_never_left_without_a_block() {
        // AppsRepository.apply writes the upserts first, then the removals; each write is
        // live at once. That order is only safe when no id is both upserted and removed.
        val starts = listOf(
            emptyList(),
            listOf(deny("a")),
            listOf(deny("b", background = true)),
            listOf(deny("a"), deny("b", background = true)),
        )
        for (start in starts) for (target in AppStatus.entries) {
            val c = change(target, start)
            val reverse = AppsLogic.reverseOf(c, start.associateBy { it.id })
            for (step in listOf(c, reverse)) {
                assertTrue("ids overlap: $start → $target", step.upsert.none { u -> step.remove.any { it.id == u.id } })
            }
            val before = AppsLogic.statusOf(start, pkg)
            if (before != AppStatus.ALLOWED && target != AppStatus.ALLOWED) {
                val midway = start.filterNot { r -> c.upsert.any { it.id == r.id } } + c.upsert
                assertTrue("open midway: $start → $target", AppsLogic.statusOf(midway, pkg) != AppStatus.ALLOWED)
            }
        }
    }

    @Test
    fun a_second_tap_on_the_same_status_changes_nothing() {
        val blocked = listOf(deny(AppsLogic.packageBlockRuleId(pkg)))
        assertTrue(change(AppStatus.BLOCKED, blocked).isEmpty)
        assertTrue(change(AppStatus.ALLOWED, emptyList()).isEmpty)
    }

    @Test
    fun undo_puts_every_rule_back_as_it_was() {
        val start = listOf(deny("a"), deny("n", domain = "ads.example.com"))
        val c = change(AppStatus.BLOCKED_IN_BACKGROUND, start)
        val after = applied(start, c)
        val reverse = AppsLogic.reverseOf(c, start.associateBy { it.id })
        val back = applied(after, reverse)
        assertEquals(start.toSet(), back.toSet())
    }

    // ── List: filter, search, sort ──────────────────────────────────────────

    @Test
    fun blocked_filter_shows_blocked_and_background_blocked_apps() {
        val items = listOf(item(pkg = "a"), item(pkg = "b", status = AppStatus.BLOCKED), item(pkg = "c", status = AppStatus.BLOCKED_IN_BACKGROUND))
        assertEquals(listOf("b", "c"), AppsLogic.shown(items, "", AppsFilter.BLOCKED, AppSort.NAME).map { it.packageName }.sorted())
    }

    @Test
    fun active_now_shows_only_apps_with_recent_traffic() {
        val items = listOf(item(pkg = "a", active = true), item(pkg = "b"))
        assertEquals(listOf("a"), AppsLogic.shown(items, "", AppsFilter.ACTIVE_NOW, AppSort.NAME).map { it.packageName })
    }

    @Test
    fun search_matches_name_or_package_ignoring_case() {
        val items = listOf(item(name = "Chrome", pkg = "com.android.chrome"), item(name = "Maps", pkg = "com.google.maps"))
        assertEquals(listOf("Chrome"), AppsLogic.shown(items, "chr", AppsFilter.ALL, AppSort.NAME).map { it.appName })
        assertEquals(listOf("Maps"), AppsLogic.shown(items, "GOOGLE", AppsFilter.ALL, AppSort.NAME).map { it.appName })
        assertEquals(2, AppsLogic.shown(items, "  ", AppsFilter.ALL, AppSort.NAME).size)
    }

    @Test
    fun default_sort_is_most_blocked_today_then_name() {
        val items = listOf(item(name = "b", pkg = "b", blockedToday = 1), item(name = "a", pkg = "a", blockedToday = 1), item(name = "c", pkg = "c", blockedToday = 9))
        assertEquals(listOf("c", "a", "b"), AppsLogic.shown(items, "", AppsFilter.ALL, AppSort.MOST_BLOCKED).map { it.appName })
        assertEquals(AppSort.MOST_BLOCKED, AppsUiState().sort)
    }

    @Test
    fun risk_sort_puts_could_watch_first_and_no_activity_last() {
        val items = listOf(
            item(name = "quiet", pkg = "q", destinations = 0),
            item(name = "normal", pkg = "n"),
            item(name = "watch", pkg = "w", stalker = 80),
            item(name = "high", pkg = "h", risk = 90),
        )
        assertEquals(listOf("watch", "high", "normal", "quiet"), AppsLogic.shown(items, "", AppsFilter.ALL, AppSort.RISK).map { it.appName })
    }

    @Test
    fun every_1_0_sort_is_still_offered() {
        // 1.0: Risk, Data, Destinations, Background (parity table §5.5 keeps them reachable).
        assertTrue(AppSort.entries.containsAll(listOf(AppSort.RISK, AppSort.DATA, AppSort.DESTINATIONS, AppSort.BACKGROUND)))
    }
}
