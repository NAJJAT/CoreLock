package com.privacyguard.app.core.blocklist

import androidx.room.Room
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.BlocklistEntity
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.repository.BlocklistRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Retiring old lists now depends on stored success times. It must only ever delete
 * lists this version no longer downloads: never a current list, also when that list's
 * last download failed, and nothing at all before any time is stored (an install
 * updated from a build without them).
 */
@RunWith(RobolectricTestRunner::class)
class RetireSourcesTest {

    private class MapTimes(val map: MutableMap<BlocklistSource, Long> = mutableMapOf()) : BlocklistUpdateTimes {
        override fun lastSuccess(source: BlocklistSource) = map[source] ?: 0L
        override fun recordSuccess(source: BlocklistSource, at: Long) { map[source] = at }
    }

    private val downloadable = BlocklistSource.values().filter { it.downloadUrl() != null }

    /** Every source name a current list or the built-in seed stores rows under. */
    private val currentNames: Set<String> =
        BlocklistSource.values().map { BlocklistManager.sourceMetadata(it).first }.toSet() +
            BlocklistManager.BUILTIN_SEED.flatMap { (source, cats) -> cats.keys.map { "$source-$it" } }

    private val retiredNames = BlocklistRepo.RETIRED_AD_SOURCES + BlocklistRepo.RETIRED_SECURITY_SOURCES

    private lateinit var db: AppDatabase
    private lateinit var config: ConfigDatabase
    private lateinit var repo: BlocklistRepo

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        config = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ConfigDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = BlocklistRepo(db.blocklistDao(), config.blocklistToggleDao())
        // One row for every current list and every retired one.
        runBlocking {
            db.blocklistDao().insertAll((currentNames + retiredNames).map {
                BlocklistEntity(domain = "${it.lowercase()}.example", source = it, category = BlocklistRepo.CAT_ADS)
            })
        }
    }

    @After
    fun tearDown() {
        db.close()
        config.close()
    }

    private fun storedSources() = runBlocking { db.blocklistDao().sources().toSet() }

    private fun retire(times: BlocklistUpdateTimes) = runBlocking {
        repo.removeSources(BlocklistManager.retiredSourceNames(BlocklistManager.succeededAtLeastOnce(times)))
    }

    @Test
    fun no_combination_of_successes_names_a_current_list() {
        // Every subset of the downloadable lists (2^7).
        for (mask in 0 until (1 shl downloadable.size)) {
            val succeeded = downloadable.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            val hit = BlocklistManager.retiredSourceNames(succeeded).filter { it in currentNames }
            assertTrue("$succeeded would delete $hit", hit.isEmpty())
        }
    }

    @Test
    fun no_stored_time_deletes_nothing() {
        retire(MapTimes())
        assertEquals(currentNames + retiredNames, storedSources())
    }

    @Test
    fun a_list_whose_last_download_failed_keeps_its_rows() = runBlocking {
        // Pro mini succeeded once, then failed today; every other list succeeded today.
        val times = MapTimes(downloadable.associateWith { 1_000L }.toMutableMap())
        BlocklistManager.refreshDue(times, dueBefore = Long.MAX_VALUE, now = { 2_000L }) { source ->
            if (source == BlocklistSource.HAGEZI_PRO_MINI) UpdateResult.Failure("offline") else UpdateResult.Success(1)
        }
        assertEquals(1_000L, times.lastSuccess(BlocklistSource.HAGEZI_PRO_MINI))

        retire(times)

        assertEquals("every current list kept, only the old ones gone", currentNames, storedSources())
    }

    @Test
    fun old_ad_lists_stay_until_both_hagezi_ad_lists_have_arrived() {
        // Pro arrived, Pro mini never did: StevenBlack and the rest are still the fallback.
        retire(MapTimes(mutableMapOf(BlocklistSource.HAGEZI_PRO to 1_000L, BlocklistSource.HAGEZI_TIF_MINI to 1_000L)))
        assertEquals(currentNames + BlocklistRepo.RETIRED_AD_SOURCES, storedSources())
    }
}
