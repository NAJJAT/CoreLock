package com.privacyguard.app.core.blocklist

import androidx.room.Room
import com.privacyguard.app.data.db.AppDatabase
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
 * ADR 0011: a download that parses fine but holds far fewer names than the stored
 * list (a broken upstream build, a truncated mirror) must not replace it. Under half
 * of the stored count is refused, the old list stays and the event is recorded.
 * A first download, with nothing stored, is always accepted.
 */
@RunWith(RobolectricTestRunner::class)
class BlocklistShrinkGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var config: ConfigDatabase
    private lateinit var repo: BlocklistRepo

    private val source = BlocklistSource.HAGEZI_PRO
    private val sourceName = BlocklistManager.sourceMetadata(source).first

    private class Recorded : BlocklistRejections {
        val events = mutableListOf<BlocklistRejection>()
        override fun record(source: BlocklistSource, stored: Int, downloaded: Int, at: Long) {
            events += BlocklistRejection(stored, downloaded, at)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        config = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ConfigDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = BlocklistRepo(db.blocklistDao(), config.blocklistToggleDao())
    }

    @After
    fun tearDown() { db.close(); config.close() }

    private fun names(n: Int, tag: String) = (0 until n).mapTo(LinkedHashSet()) { "$tag$it.example" }

    private fun stored() = runBlocking { repo.countBySource(sourceName) }

    private fun store(domains: Set<String>, log: Recorded) =
        runBlocking { BlocklistManager.storeDownloaded(source, domains, repo, log, now = 42L) }

    @Test
    fun a_one_line_download_does_not_replace_200000_names() {
        val log = Recorded()
        assertTrue(store(names(200_000, "old"), log) is UpdateResult.Success)

        val result = store(setOf("only.example"), log)

        assertTrue("must count as a failure so the worker retries: $result", result is UpdateResult.Failure)
        assertEquals(200_000, stored())
        assertEquals(listOf(BlocklistRejection(200_000, 1, 42L)), log.events)
    }

    @Test
    fun a_download_of_150000_replaces_200000() {
        val log = Recorded()
        store(names(200_000, "old"), log)

        assertTrue(store(names(150_000, "new"), log) is UpdateResult.Success)

        assertEquals(150_000, stored())
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun a_first_download_is_never_rejected() {
        val log = Recorded()
        assertTrue(store(setOf("a.example", "b.example", "c.example"), log) is UpdateResult.Success)
        assertEquals(3, stored())
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun exactly_half_is_accepted_and_just_under_half_is_not() {
        val log = Recorded()
        store(names(1_000, "old"), log)
        assertTrue(store(names(499, "a"), log) is UpdateResult.Failure)
        assertEquals(1_000, stored())
        assertTrue(store(names(500, "b"), log) is UpdateResult.Success)
        assertEquals(500, stored())
    }

    @Test
    fun the_rejection_is_kept_for_diagnostics() {
        val prefs = PrefsBlocklistRejections(RuntimeEnvironment.getApplication())
        prefs.record(source, 200_000, 1, 42L)
        assertEquals(BlocklistRejection(200_000, 1, 42L), prefs.last(source))
    }
}
