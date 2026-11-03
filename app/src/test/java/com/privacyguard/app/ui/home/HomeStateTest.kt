package com.privacyguard.app.ui.home

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.DnsQueryEntity
import com.privacyguard.app.data.repository.BlockStats
import com.privacyguard.app.ui.components.ProtectionState
import com.privacyguard.app.vpn.BootReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HomeStateTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        BootReceiver.markVpnStopped(app)
        app.getSharedPreferences("corelock_home", Context.MODE_PRIVATE).edit().clear().commit()
        resetHomePreferences()
    }

    /** §6.1 "no false 'Not protected' flash": the state is there before anything collects. */
    @Test
    fun hero_state_is_known_at_the_first_frame() {
        BootReceiver.markVpnStopped(app)
        assertEquals(ProtectionState.OFF, HomeViewModel(app).uiState.value.protection)
        // Protection should be on but no service runs (a crash): red at once, not "off" first.
        BootReceiver.markVpnActive(app)
        assertEquals(ProtectionState.STOPPED_UNEXPECTEDLY, HomeViewModel(app).uiState.value.protection)
    }

    @Test
    fun blocked_count_is_unknown_until_the_database_answers_never_zero() {
        assertEquals(BlockedToday.Loading, HomeViewModel(app).uiState.value.blockedToday)
    }

    @Test
    fun blocked_today_is_blockstats_from_local_midnight() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val zoneId = ZoneId.of("Asia/Amman")
            val zone = java.util.TimeZone.getTimeZone(zoneId)
            val now = LocalDateTime.of(2026, 11, 3, 9, 0).atZone(zoneId).toInstant().toEpochMilli()
            val midnight = HomeLogic.startOfDay(now, zone)
            fun lookup(at: Long, blocked: Boolean) = DnsQueryEntity(
                timestamp = at, appPackage = "com.example.a", appName = "A",
                domain = "ads.example.com", wasBlocked = blocked, phoneWasIdle = false,
            )
            fun connection(at: Long, blocked: Boolean) = ConnectionEntity(
                appUid = 10_123, packageName = "com.example.a", destinationIp = "1.2.3.4",
                domain = "tracker.example.net", timestamp = at, wasBlocked = blocked,
            )
            listOf(
                lookup(now - 1_000, true), lookup(midnight + 1, true), lookup(now - 5_000, false),
                lookup(midnight - 1, true), // yesterday, 23:59:59.999
            ).forEach { db.dnsQueryDao().insert(it) }
            db.connectionDao().insertAll(listOf(connection(now - 2_000, true), connection(midnight - 60_000, true)))

            val stats = BlockStats(db)
            val count = HomeViewModel.blockedTodayCount(stats, now, zone)
            assertEquals("2 lookups + 1 connection since midnight", 3, count)
            // The one definition of "blocked" every screen uses.
            assertEquals(stats.between(midnight, now).total, count)
        } finally {
            db.close()
        }
    }

    @Test
    fun whats_new_shows_after_an_update_never_after_a_fresh_install() {
        val prefs = app.getSharedPreferences("corelock_home", Context.MODE_PRIVATE)

        val update = resetHomePreferences()
        update.decideWhatsNew(onboardingCompleted = true) // a 1.0 user: onboarding done before 1.1 ran
        assertTrue(update.whatsNew.value)
        update.finishWhatsNew()
        assertFalse(update.whatsNew.value)
        val restarted = resetHomePreferences().also { it.decideWhatsNew(onboardingCompleted = true) }
        assertFalse("stays done after a restart", restarted.whatsNew.value)

        prefs.edit().clear().commit()
        resetHomePreferences().decideWhatsNew(onboardingCompleted = false) // a new install
        // Onboarding completes later: the next start must not say "some things moved".
        val later = resetHomePreferences().also { it.decideWhatsNew(onboardingCompleted = true) }
        assertFalse(later.whatsNew.value)
    }

    /** A new instance reads the stored file, like the next app start. */
    private fun resetHomePreferences(): HomePreferences {
        HomePreferences::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        return HomePreferences.getInstance(app)
    }
}
