package com.privacyguard.app.core.blocklist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A list that fails is retried, and only that list: a failed source made the whole
 * update "succeed" anyway, so it stayed missing until the next app start.
 */
class BlocklistRefreshTest {

    private class MapTimes : BlocklistUpdateTimes {
        val map = mutableMapOf<BlocklistSource, Long>()
        override fun lastSuccess(source: BlocklistSource) = map[source] ?: 0L
        override fun recordSuccess(source: BlocklistSource, at: Long) { map[source] = at }
    }

    private val downloadable = BlocklistSource.values().filter { it.downloadUrl() != null }.toSet()

    private fun refresh(
        times: BlocklistUpdateTimes, dueBefore: Long, now: Long, failing: Set<BlocklistSource> = emptySet(),
        fetched: MutableList<BlocklistSource> = mutableListOf(),
    ) = runBlocking {
        BlocklistManager.refreshDue(times, dueBefore, now = { now }) { source ->
            fetched += source
            if (source in failing) UpdateResult.Failure("offline") else UpdateResult.Success(10)
        }
    }

    @Test
    fun one_failed_list_fails_the_run_and_keeps_the_others() {
        val times = MapTimes()
        val outcome = refresh(times, dueBefore = Long.MAX_VALUE, now = 1_000, failing = setOf(BlocklistSource.HAGEZI_PRO_MINI))

        assertEquals(setOf(BlocklistSource.HAGEZI_PRO_MINI), outcome.failed)
        assertEquals(downloadable - BlocklistSource.HAGEZI_PRO_MINI, outcome.updated)
        assertTrue(outcome.toResult() is UpdateResult.Failure)
        assertEquals(0L, times.lastSuccess(BlocklistSource.HAGEZI_PRO_MINI))
        assertEquals(1_000L, times.lastSuccess(BlocklistSource.HAGEZI_TIF_MINI))
    }

    @Test
    fun the_retry_fetches_only_the_list_that_failed() {
        val times = MapTimes()
        val requestedAt = 500L
        refresh(times, dueBefore = requestedAt, now = 1_000, failing = setOf(BlocklistSource.HAGEZI_PRO_MINI))

        val fetched = mutableListOf<BlocklistSource>()
        val retry = refresh(times, dueBefore = requestedAt, now = 2_000, fetched = fetched)

        assertEquals(listOf(BlocklistSource.HAGEZI_PRO_MINI), fetched)
        assertTrue(retry.toResult() is UpdateResult.Success)
        assertEquals(2_000L, times.lastSuccess(BlocklistSource.HAGEZI_PRO_MINI))
    }

    @Test
    fun nothing_due_downloads_nothing_and_succeeds_with_zero_entries() {
        val times = MapTimes().apply { downloadable.forEach { map[it] = 5_000L } }
        val fetched = mutableListOf<BlocklistSource>()
        val outcome = refresh(times, dueBefore = 4_000, now = 6_000, fetched = fetched)

        assertTrue(fetched.isEmpty())
        assertEquals(UpdateResult.Success(0), outcome.toResult())
    }

    @Test
    fun a_success_time_in_the_future_counts_as_due() {
        // Stored while the clock was a year ahead, then corrected: without the check the
        // list would look fresh for a year.
        val year = 365L * 24 * 60 * 60 * 1000
        val times = MapTimes().apply { downloadable.forEach { map[it] = 1_000L } }
        times.map[BlocklistSource.HAGEZI_PRO_MINI] = 1_000L + year
        val fetched = mutableListOf<BlocklistSource>()

        refresh(times, dueBefore = 500L, now = 2_000L, fetched = fetched)

        assertEquals(listOf(BlocklistSource.HAGEZI_PRO_MINI), fetched)
        assertEquals(2_000L, times.lastSuccess(BlocklistSource.HAGEZI_PRO_MINI))
    }

    @Test
    fun all_lists_succeeding_is_a_success() {
        val outcome = refresh(MapTimes(), dueBefore = Long.MAX_VALUE, now = 1)
        assertEquals(UpdateResult.Success(10 * downloadable.size), outcome.toResult())
    }
}
