package com.privacyguard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Navigation parity (DESIGN_SPEC_1.1.md §4.3, §5.1 and §9): every 1.0 route still exists in
 * the 1.1 graph, so links and notifications posted by 1.0 keep working after the update;
 * every route has a way in; and the bar has exactly the four tabs of 1.1.
 *
 * It reads the source, like BrandNameTest, because the graph needs a running activity to
 * build and a route that is never declared only fails when someone taps it.
 */
class NavigationParityTest {

    private val navHost = File("src/main/java/com/privacyguard/ui/AppNavHost.kt").readText()
    private val mainActivity = File("src/main/java/com/privacyguard/app/MainActivity.kt").readText()

    /** Routes declared with composable("…"), the multi-line form included. */
    private val declared: Set<String> =
        Regex("""composable\(\s*"([^"]+)"""").findAll(navHost).map { it.groupValues[1] }.toSet()

    /** "appDetail/{packageName}?appName={appName}" → "appDetail"; "settings/blocklists" stays. */
    private fun base(route: String) = route.substringBefore('?').substringBefore("/{")

    private val declaredBases = declared.map(::base).toSet()

    /** Where navigate("…") and the route helpers lead, by base. */
    private val navigatedBases: Set<String> =
        Regex("""navigate\("([^"$]+)""").findAll(navHost).map { base(it.groupValues[1].trimEnd('/')) }.toSet() +
            (if (navHost.contains("navigate(appTrafficRoute(")) setOf("appTraffic") else emptySet())

    @Test
    fun every_1_0_route_of_the_parity_table_is_still_declared() {
        val routes1_0 = listOf(
            "onboarding", "dashboard", "apps", "appDetail", "appTraffic", "connections", "domain",
            "statistics", "payloads", "settings", "alerts", "evidence", "sleepReport", "ads",
            "adPrivacy", "rules", "crypto", "licenses", "licenseText",
        )
        val missing = routes1_0 - declaredBases
        assertTrue("1.0 routes missing from the 1.1 graph: $missing", missing.isEmpty())
    }

    @Test
    fun the_new_1_1_places_are_declared() {
        val routes1_1 = listOf(
            "securityOverview", "trafficLive", "fix", "settings/blocklists", "settings/killswitch",
            "advanced", "advanced/network", "advanced/proxies", "advanced/analysis", "advanced/diagnostics",
        )
        val missing = routes1_1 - declaredBases
        assertTrue("1.1 routes missing: $missing", missing.isEmpty())
    }

    @Test
    fun every_route_a_notification_may_open_is_declared() {
        val set = Regex("""NOTIFICATION_ROUTES\s*=\s*setOf\(([^)]*)\)""").find(mainActivity)
        requireNotNull(set) { "NOTIFICATION_ROUTES not found in MainActivity" }
        val routes = Regex(""""([^"]+)"""").findAll(set.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(listOf("alerts", "crypto", "connections", "apps"), routes)
        val missing = routes - declaredBases
        assertTrue("notification routes not declared: $missing", missing.isEmpty())
    }

    @Test
    fun the_bar_has_the_four_tabs_of_1_1_in_order() {
        val tabs = Regex("""NavTab\("([^"]+)"""").findAll(navHost.substringAfter("APP_TABS = listOf("))
            .map { it.groupValues[1] }.take(4).toList()
        assertEquals(listOf("dashboard", "apps", "connections", "settings"), tabs)
        assertTrue("the bar must use APP_TABS", navHost.contains("val tabs = APP_TABS"))
        // Stats and Payload left the bar; their routes stay (checked above).
        assertTrue(!navHost.contains("R.string.nav_stats") && !navHost.contains("R.string.nav_payload"))
    }

    @Test
    fun every_declared_route_has_a_way_in() {
        // onboarding is the start destination for a first run; the tabs come from the bar.
        val ways = navigatedBases + APP_TABS.map { it.route } + "onboarding"
        val unreachable = declaredBases - ways
        assertTrue("routes nothing navigates to: $unreachable", unreachable.isEmpty())
        // And nothing navigates to a route that is not declared (it would crash).
        val undeclared = navigatedBases - declaredBases
        assertTrue("navigate() to undeclared routes: $undeclared", undeclared.isEmpty())
    }

    @Test
    fun the_screens_moved_out_of_the_bar_keep_their_entry_points() {
        // Statistics and HTTPS inspection are reached from Advanced (and the What's new card).
        assertTrue(navHost.contains("navigate(\"statistics\")"))
        assertTrue(navHost.contains("navigate(\"payloads\")"))
    }
}
