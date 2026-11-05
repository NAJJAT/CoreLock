package com.privacyguard.app.data.repository

import androidx.room.Room
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsQueryEntity
import com.privacyguard.app.ui.home.HomeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * "Blocked today" is shown by the notification, Home, Activity, Statistics, Apps and Ads
 * (owner, 2026-11-04): one source, [BlockStats.today], one window, local midnight to now.
 * The first part checks the numbers on real rows around midnight; the second that every
 * one of those places reads that source and no private window of its own.
 */
@RunWith(RobolectricTestRunner::class)
class BlockCountAgreementTest {

    private lateinit var db: AppDatabase
    private val zone = TimeZone.getTimeZone("Europe/Amsterdam")
    private val minute = 60_000L

    // 2026-11-04 10:00 in [zone]; midnight is 10 hours earlier.
    private val now = Calendar.getInstance(zone).apply {
        clear(); set(2026, Calendar.NOVEMBER, 4, 10, 0, 0)
    }.timeInMillis
    private val midnight = now - 10 * 60 * minute

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun lookup(at: Long, pkg: String, blocked: Boolean = true) = DnsQueryEntity(
        timestamp = at, appPackage = pkg, appName = pkg,
        domain = "ads.example.com", wasBlocked = blocked, phoneWasIdle = false,
    )

    private fun connection(at: Long, pkg: String, blocked: Boolean = true) = ConnectionEntity(
        appUid = 10_123, packageName = pkg, destinationIp = "192.0.2.1",
        domain = "tracker.example.net", timestamp = at, wasBlocked = blocked,
    )

    private fun seed() = runBlocking {
        listOf(
            lookup(midnight + minute, "com.example.a"),        // today
            lookup(now - minute, "com.example.b"),              // today
            lookup(now - 2 * minute, "com.example.a", blocked = false),
            lookup(midnight - minute, "com.example.a"),         // yesterday 23:59: not today
        ).forEach { db.dnsQueryDao().insert(it) }
        db.connectionDao().insertAll(listOf(
            connection(midnight + 1, "com.example.b"),          // just after midnight: today
            // Exactly midnight belongs to the day before: every window is (from, to], the
            // same rule the lists use, so a count and its list never differ by that row.
            connection(midnight, "com.example.b"),
            connection(now - 3 * minute, "com.example.a", blocked = false),
            connection(midnight - 1, "com.example.b"),          // a moment before: not today
        ))
    }

    @Test
    fun today_starts_at_local_midnight() {
        assertEquals(midnight, BlockStats.startOfDay(now, zone))
        seed()
        assertEquals("2 lookups + 1 connection since midnight", 3, runBlocking { BlockStats(db).today(now, zone) })
    }

    @Test
    fun home_and_the_per_app_numbers_agree_with_today() = runBlocking {
        seed()
        val stats = BlockStats(db)
        val today = stats.today(now, zone)
        assertEquals("Home", today, HomeViewModel.blockedTodayCount(stats, now, zone))
        assertEquals("Apps: the per-app numbers add up to Home's", today, stats.perAppToday(now, zone).values.sum())
        assertEquals(mapOf("com.example.a" to 1, "com.example.b" to 2), stats.perAppToday(now, zone))
    }

    @Test
    fun every_place_that_shows_blocked_today_reads_the_one_source() {
        val places = mapOf(
            "notification" to "src/main/java/com/privacyguard/platform/android/PrivacyVpnService.kt",
            "Home" to "src/main/java/com/privacyguard/app/ui/home/HomeViewModel.kt",
            "Activity" to "src/main/java/com/privacyguard/app/ui/activity/ActivityRepository.kt",
            "Statistics" to "src/main/java/com/privacyguard/app/ui/statistics/StatisticsViewModel.kt",
            "Ads" to "src/main/java/com/privacyguard/app/ui/ads/AdsViewModel.kt",
        )
        places.forEach { (place, path) ->
            val code = File(path).readText()
            assertTrue("$place reads BlockStats.today", code.contains(".today("))
            assertFalse("$place has no window of its own", code.contains("totalBetween("))
        }
        val apps = File("src/main/java/com/privacyguard/app/ui/apps/AppsRepository.kt").readText()
        assertTrue("Apps reads BlockStats.perAppToday", apps.contains(".perAppToday("))
        assertFalse("Apps has no window of its own", apps.contains("perAppBetween("))
        // The Statistics tile opens Home's list, not a 24-hour one.
        val statsScreen = File("src/main/java/com/privacyguard/app/ui/statistics/StatisticsScreen.kt").readText()
        assertTrue(statsScreen.contains("R.string.stats_blocked_today), fmtCount(stats.blockedToday), PgDanger, Modifier.weight(1f).clickable(onClick = onOpenBlocked)"))
        // Security systems (Advanced › Analysis) showed "Blocked (24 h)": 76 next to Home's 5.
        val systems = File("src/main/java/com/privacyguard/app/ui/dashboard/DashboardViewModel.kt").readText()
        assertTrue("Security systems reads BlockStats.todayCounts", systems.contains(".todayCounts("))
        assertFalse("Security systems has no 24-hour block window", systems.contains("BLOCKED_24H.windowMs"))
        val systemsScreen = File("src/main/java/com/privacyguard/app/ui/dashboard/DashboardScreen.kt").readText()
        assertTrue(systemsScreen.contains("StatTile(stringResource(R.string.stats_blocked_today), formatCount(uiState.trackersBlocked)"))
    }

    @Test
    fun today_counts_match_today() = runBlocking {
        seed()
        val stats = BlockStats(db)
        assertEquals(stats.today(now, zone), stats.todayCounts(now, zone).total)
    }

    @Test
    fun the_notification_says_today_in_every_language() {
        listOf("values", "values-ar", "values-fr", "values-nl").forEach { dir ->
            val xml = File("src/main/res/$dir/strings_notifications.xml").readText()
            val plural = xml.substringAfter("name=\"notif_blocked_n\"").substringBefore("</plurals>")
            assertFalse("$dir: no 24-hour wording left", plural.contains("24"))
        }
    }
}
