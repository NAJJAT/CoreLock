package com.privacyguard.app.workers

import android.content.Context
import androidx.work.NetworkType
import com.privacyguard.app.core.blocklist.BlocklistSource
import com.privacyguard.app.core.blocklist.PrefsBlocklistUpdateTimes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

/**
 * The periodic refresh was never scheduled, so lists stayed as first downloaded. Weekly
 * on unmetered networks; any network once a list has had no success for 14 days.
 */
@RunWith(RobolectricTestRunner::class)
// As AppLanguageTest: keep Robolectric's Conscrypt out of the plain-JVM TLS tests.
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class BlocklistUpdateWorkerTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 100 * day
    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        context.getSharedPreferences("blocklist_updates", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun every_app_start_keeps_one_job_of_each_kind() {
        // Robolectric does not run WorkManager's start-up initializer.
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder().setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor()).build(),
            )
        }
        // Two app starts: KEEP must not add a second job or reset the first.
        BlocklistUpdateWorker.schedulePeriodic(context)
        BlocklistUpdateWorker.schedulePeriodic(context)
        val workManager = androidx.work.WorkManager.getInstance(context)
        for (name in listOf(BlocklistUpdateWorker.WORK_NAME, BlocklistUpdateWorker.STALE_WORK_NAME)) {
            val infos = workManager.getWorkInfosForUniqueWork(name).get()
            assertEquals(name, 1, infos.size)
        }
    }

    @Test
    fun weekly_job_needs_an_unmetered_network_and_skips_lists_from_this_week() {
        val spec = BlocklistUpdateWorker.weeklyRequest().workSpec
        assertEquals(NetworkType.UNMETERED, spec.constraints.requiredNetworkType)
        assertTrue(spec.constraints.requiresBatteryNotLow())
        assertEquals(7 * day, spec.intervalDuration)
        assertEquals(now - 6 * day, BlocklistUpdateWorker.dueBefore(spec.input, now))
    }

    @Test
    fun stale_job_runs_on_any_network_but_only_for_lists_older_than_14_days() {
        val spec = BlocklistUpdateWorker.staleRequest().workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(day, spec.intervalDuration)
        assertEquals(now - 14 * day, BlocklistUpdateWorker.dueBefore(spec.input, now))
    }

    @Test
    fun first_download_and_refresh_now_fetch_every_list_on_any_network() {
        val spec = BlocklistUpdateWorker.nowRequest(requestedAt = now).workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        // Every list older than the request; a retry an hour later still uses the request time.
        assertEquals(now, BlocklistUpdateWorker.dueBefore(spec.input, now + day / 24))
    }

    @Test
    fun the_last_success_survives_a_restart() {
        PrefsBlocklistUpdateTimes(context).recordSuccess(BlocklistSource.HAGEZI_PRO_MINI, now)
        assertEquals(now, PrefsBlocklistUpdateTimes(context).lastSuccess(BlocklistSource.HAGEZI_PRO_MINI))
        assertEquals(0L, PrefsBlocklistUpdateTimes(context).lastSuccess(BlocklistSource.HAGEZI_PRO))
    }
}
