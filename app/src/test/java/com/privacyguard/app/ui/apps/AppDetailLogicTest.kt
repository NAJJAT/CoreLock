package com.privacyguard.app.ui.apps

import com.privacyguard.app.ui.activity.ActivityAction
import com.privacyguard.app.ui.activity.ActivityItem
import com.privacyguard.app.ui.activity.ActivityLogic
import com.privacyguard.app.ui.activity.ActivityReason
import com.privacyguard.core.session.BlockSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN_SPEC_1.1.md §6.4: "Connected recently", grouped by organisation and purpose. */
class AppDetailLogicTest {

    private val pkg = "com.example.app"

    private fun row(
        id: String, host: String?, title: String = host.orEmpty(), time: Long, blocked: Boolean = false,
        lookup: Boolean = false, count: Int = 1, ip: String? = "203.0.113.1",
    ) = ActivityItem(
        id = id, isLookup = lookup, timestamp = time, packageName = if (lookup) "" else pkg, appName = "App",
        host = host, ip = if (lookup) null else ip, port = if (lookup) null else 443, protocol = if (lookup) null else "TCP",
        blocked = blocked, blockSource = if (blocked) (if (lookup) BlockSource.DNS else BlockSource.SNI) else null,
        reason = if (blocked) ActivityReason.ADS_LIST else ActivityReason.ALLOWED, title = title, count = count,
    )

    @Test
    fun one_row_per_name_counting_every_attempt() {
        val rows = AppDetailLogic.perName(
            listOf(
                row("c1", "a.example.com", time = 10, count = 3),
                row("c2", "a.example.com", time = 20, blocked = true, count = 2),
            ),
        )
        val only = rows.single()
        assertEquals(5, only.count)
        assertEquals(20L, only.timestamp)
    }

    @Test
    fun the_blocked_attempt_is_the_one_shown_so_allow_is_offered() {
        val shown = AppDetailLogic.perName(
            listOf(
                row("c1", "ads.example.com", time = 30),  // newer, allowed
                row("c2", "ads.example.com", time = 20, blocked = true),
            ),
        ).single()
        assertTrue(shown.blocked)
        assertTrue(ActivityAction.ALLOW_FOR_APP in ActivityLogic.actionsFor(shown))
        assertEquals(30L, shown.timestamp)
    }

    @Test
    fun a_name_also_blocked_as_a_lookup_can_only_be_allowed_everywhere() {
        // D3: a per-app Allow acts only on connections; the lookup would stay blocked.
        val shown = AppDetailLogic.perName(
            listOf(
                row("c1", "ads.example.com", time = 20, blocked = true),
                row("d1", "ads.example.com", time = 10, blocked = true, lookup = true),
            ),
        ).single()
        assertTrue(ActivityLogic.everywhereOnly(shown))
        assertEquals(listOf(ActivityAction.ALLOW_EVERYWHERE, ActivityAction.KEEP_BLOCKED), ActivityLogic.actionsFor(shown))
    }

    @Test
    fun sections_group_by_organisation_newest_first_with_other_last() {
        val sections = AppDetailLogic.sections(
            listOf(
                row("c1", "googleads.example", "Google ads", time = 10, blocked = true),
                row("c2", "unknown.example", time = 50),
                row("c3", "pagead.example", "Google ads", time = 30, blocked = true),
                row("c4", "graph.example", "Meta services", time = 40),
            ),
        )
        assertEquals(listOf("Meta services", "Google ads", null), sections.map { it.title })
        assertEquals(listOf("pagead.example", "googleads.example"), sections[1].items.map { it.host })
        assertNull(sections.last().title)
    }

    @Test
    fun an_apps_own_names_group_under_their_owner_not_other() {
        val sections = AppDetailLogic.sections(
            listOf(
                row("c1", "update.googleapis.com", time = 2).copy(company = "Google"),
                row("c2", "unknown.example", time = 1),
            ),
        )
        assertEquals(listOf("Google", null), sections.map { it.title })
    }

    @Test
    fun an_address_only_row_keeps_its_place() {
        val sections = AppDetailLogic.sections(listOf(row("c1", host = null, title = "203.0.113.9", time = 1, ip = "203.0.113.9")))
        assertEquals("203.0.113.9", sections.single().items.single().ip)
    }

    @Test
    fun nothing_in_the_window_gives_no_sections() = assertTrue(AppDetailLogic.sections(emptyList()).isEmpty())
}
