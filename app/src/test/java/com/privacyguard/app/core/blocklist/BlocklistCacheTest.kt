package com.privacyguard.app.core.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class BlocklistCacheTest {

    private val file = File.createTempFile("blocklist", ".txt.gz").also { it.deleteOnExit() }

    @Test
    fun round_trip_keeps_both_lists_apart() {
        BlocklistCache.write(file, listOf("ads.example", "*.tracker.example"), listOf("bad.example"))
        assertEquals(listOf("ads.example", "*.tracker.example") to listOf("bad.example"), BlocklistCache.read(file))
    }

    @Test
    fun a_missing_or_foreign_file_is_not_trusted() {
        assertNull(BlocklistCache.read(File(file.parentFile, "does-not-exist.gz")))
        java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write("something else\nads.example\n".toByteArray()) }
        assertNull(BlocklistCache.read(file))
    }

    @Test
    fun a_cut_off_file_is_not_trusted() {
        // No security marker: the write stopped half way.
        java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write("privacyguard-blocklist-cache 1\nads.example\n".toByteArray()) }
        assertNull(BlocklistCache.read(file))
    }
}
