package com.privacyguard.app.data.repository

import androidx.room.Room
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsQueryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The ongoing notification shows [BlockStats.totalBetween]; the dashboard's
 * "Blocked (24 h)" tile shows [BlockStats.between]'s total. They must be the same number.
 */
@RunWith(RobolectricTestRunner::class)
class BlockStatsTotalTest {

    private lateinit var db: AppDatabase
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun lookup(at: Long, blocked: Boolean) = DnsQueryEntity(
        timestamp = at, appPackage = "com.example.a", appName = "A",
        domain = "ads.example.com", wasBlocked = blocked, phoneWasIdle = false,
    )

    private fun connection(at: Long, blocked: Boolean) = ConnectionEntity(
        appUid = 10_123, packageName = "com.example.a", destinationIp = "1.2.3.4",
        domain = "tracker.example.net", timestamp = at, wasBlocked = blocked,
    )

    @Test
    fun notification_total_matches_the_dashboard_total() = runBlocking {
        listOf(
            lookup(now - 1_000, blocked = true), lookup(now - 2_000, blocked = true),
            lookup(now - 3_000, blocked = false),
            lookup(now - day - 1, blocked = true),          // older than 24 h
        ).forEach { db.dnsQueryDao().insert(it) }
        db.connectionDao().insertAll(listOf(
            connection(now - 5_000, blocked = true),
            connection(now - 6_000, blocked = false),
            connection(now - 2 * day, blocked = true),      // older than 24 h
        ))
        val stats = BlockStats(db)
        val total = stats.totalBetween(now - day, now)
        assertEquals("2 lookups + 1 connection in the window", 3, total)
        assertEquals(stats.between(now - day, now).total, total)
    }
}
