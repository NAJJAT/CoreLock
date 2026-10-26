package com.privacyguard.app.data.repository

import androidx.room.Room
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.db.BlocklistEntity
import com.privacyguard.app.data.db.ConfigDatabase
import com.privacyguard.app.data.db.DomainRow
import com.privacyguard.core.filter.FilterEngine.BlockLevel
import com.privacyguard.core.filter.ProtectionLevels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch

/**
 * The big list reads go page by page so a stopped VPN service can give up its read
 * (BlocklistRepo.paged). They must return exactly what one query returned.
 */
@RunWith(RobolectricTestRunner::class)
class BlocklistPagedReadTest {

    private lateinit var db: AppDatabase
    private lateinit var config: ConfigDatabase
    private lateinit var repo: BlocklistRepo

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        config = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ConfigDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BlocklistRepo(db.blocklistDao(), config.blocklistToggleDao())
    }

    @After
    fun tearDown() {
        db.close()
        config.close()
    }

    /** More rows than one page, across categories, sources and the enabled flag. */
    private fun fill(): List<BlocklistEntity> {
        val categories = listOf(BlocklistRepo.CAT_ADS, BlocklistRepo.CAT_TRACKERS, BlocklistRepo.CAT_MALWARE, BlocklistRepo.CAT_DOH)
        val sources = ProtectionLevels.excludedSourcesFor(BlockLevel.STANDARD).toList() + listOf("Hagezi-ProMini", "OISD")
        val rows = (0 until BlocklistRepo.PAGE_ROWS * 2 + 123).map { i ->
            BlocklistEntity(
                domain = "d$i.example", source = sources[i % sources.size],
                category = categories[i % categories.size], isEnabled = i % 7 != 0,
            )
        }
        runBlocking { rows.chunked(5_000).forEach { db.blocklistDao().insertAll(it) } }
        return rows
    }

    @Test
    fun paged_level_read_returns_what_one_query_returned() = runBlocking {
        val rows = fill()
        for (level in BlockLevel.entries) {
            val categories = ProtectionLevels.categoriesFor(level) - BlocklistRepo.CAT_MALWARE
            val excluded = ProtectionLevels.excludedSourcesFor(level)
            val expected = rows.filter { it.isEnabled && it.category in categories && it.source !in excluded }.map { it.domain }
            val got = repo.levelListDomains(level)
            assertEquals("$level", expected.sorted(), got.sorted())
        }
    }

    @Test
    fun paged_tracker_read_returns_what_one_query_returned() = runBlocking {
        val rows = fill()
        val expected = rows.filter { it.isEnabled && it.category !in BlocklistRepo.BYPASS_CATEGORIES }.map { it.domain }
        val got = repo.domainsNotIn(BlocklistRepo.BYPASS_CATEGORIES)
        assertTrue(got.size > BlocklistRepo.PAGE_ROWS)
        assertEquals(expected.sorted(), got.sorted())
    }

    @Test
    fun a_cancelled_read_stops_between_pages() = runBlocking {
        var pages = 0
        val firstPageStarted = CompletableDeferred<Unit>()
        // A running SQL query does not notice a cancel: the page blocks its thread.
        val queryRunning = CountDownLatch(1)
        val read = async(Dispatchers.Default) {
            BlocklistRepo.paged { after, limit ->
                pages++
                if (pages == 1) { firstPageStarted.complete(Unit); queryRunning.await() }
                val n = if (pages < 3) limit else 1   // three pages in all
                List(n) { DomainRow(after.coerceAtLeast(0) + it + 1, "x$it.example") }
            }
        }
        firstPageStarted.await()
        read.cancel()   // the service stops while the first page is read
        queryRunning.countDown()
        assertTrue(runCatching { read.await() }.exceptionOrNull() is CancellationException)
        assertEquals("no page after the cancel", 1, pages)
    }
}
