package com.privacyguard.app.vpn

import com.privacyguard.app.core.stats.StatsManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A lookup that fails with an error is counted; a plain "no match" is not. */
class UidMapperErrorTest {

    @Before fun setUp() = StatsManager.reset()

    @Test fun unreadable_table_is_counted() {
        // A directory exists and is readable, but opening it as a file throws.
        val dir = Files.createTempDirectory("proc-net").toFile()
        try {
            assertEquals(-1, UidMapper.scanProcFile(dir, 443, null))
            StatsManager.publishNow()
            assertEquals(1L, StatsManager.snapshot.value.ownerLookupErrors)
        } finally { dir.delete() }
    }

    @Test fun no_match_is_not_an_error() {
        val f = File.createTempFile("tcp", "")
        try {
            f.writeText("  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n" +
                "   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 1 1\n")
            assertEquals(-1, UidMapper.scanProcFile(f, 443, null))
            assertEquals(10123, UidMapper.scanProcFile(f, 0x1F90, null))
            StatsManager.publishNow()
            assertEquals(0L, StatsManager.snapshot.value.ownerLookupErrors)
        } finally { f.delete() }
    }
}
