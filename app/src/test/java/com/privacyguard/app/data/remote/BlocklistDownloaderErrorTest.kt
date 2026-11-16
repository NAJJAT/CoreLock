package com.privacyguard.app.data.remote

import com.privacyguard.app.core.stats.StatsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** A failed download is counted, and a cancelled one stops instead of retrying. */
class BlocklistDownloaderErrorTest {

    private var opens = 0

    @Before fun setUp() { StatsManager.reset(); opens = 0 }

    @Test fun network_errors_are_counted_per_attempt_and_give_null() = runBlocking {
        val d = BlocklistDownloader({ opens++; throw IOException("offline") }, { 0L })
        assertNull(d.downloadFrom("https://example.invalid/list.txt") { it.count() })
        StatsManager.publishNow()
        assertEquals(3, opens)
        assertEquals(3L, StatsManager.snapshot.value.listDownloadErrors)
    }

    @Test fun cancellation_is_not_swallowed_and_not_retried() {
        val d = BlocklistDownloader({ opens++; throw CancellationException("worker stopped") }, { 0L })
        try {
            runBlocking { d.downloadFrom("https://example.invalid/list.txt") { it.count() } }
            fail("cancellation was swallowed")
        } catch (_: CancellationException) { /* expected */ }
        StatsManager.publishNow()
        assertEquals(1, opens)
        assertEquals(0L, StatsManager.snapshot.value.listDownloadErrors)
    }
}
