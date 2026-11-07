package com.privacyguard.app.core.blocklist

import com.privacyguard.vpn.firewall.DomainFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BlocklistCacheTest {

    private val file = File.createTempFile("blocklist", ".v2.bin").also { it.deleteOnExit() }

    private fun filters(): Pair<DomainFilter, DomainFilter> =
        DomainFilter().apply { rebuild(listOf("ads.example", "*.tracker.example")) } to
            DomainFilter().apply { rebuild(listOf("bad.example")) }

    @Test
    fun round_trip_keeps_both_lists_apart_and_blocks_the_same() {
        val (blocked, security) = filters()
        BlocklistCache.write(file, blocked, security)
        val snap = BlocklistCache.read(file)
        assertNotNull(snap)
        snap!!
        assertArrayEquals(blocked.fingerprints().first, snap.blockedNames)
        assertArrayEquals(blocked.fingerprints().second, snap.blockedWildcards)
        val (b2, s2) = DomainFilter() to DomainFilter()
        snap.applyTo(b2, s2)
        assertTrue(b2.isBlocked("x.ads.example") && b2.isBlocked("tracker.example"))
        assertFalse(b2.isBlocked("bad.example"))
        assertTrue(s2.isBlocked("bad.example"))
        assertFalse(s2.isBlocked("ads.example"))
        assertTrue(b2.size == 2L && s2.size == 1L)
    }

    @Test
    fun a_missing_foreign_or_version_1_file_is_not_trusted() {
        assertNull(BlocklistCache.read(File(file.parentFile, "does-not-exist.bin")))
        java.util.zip.GZIPOutputStream(file.outputStream()).use {
            it.write("privacyguard-blocklist-cache 1\nads.example\n--security--\nbad.example\n".toByteArray())
        }
        assertNull(BlocklistCache.read(file))
    }

    @Test
    fun a_cut_off_file_is_not_trusted() {
        val (blocked, security) = filters()
        BlocklistCache.write(file, blocked, security)
        val whole = file.readBytes()
        for (len in listOf(0, 10, whole.size - 12, whole.size - 1)) {
            file.writeBytes(whole.copyOf(len))
            assertNull("cut at $len", BlocklistCache.read(file))
        }
    }

    @Test
    fun a_damaged_file_is_not_trusted() {
        val (blocked, security) = filters()
        BlocklistCache.write(file, blocked, security)
        val whole = file.readBytes()
        // Any flipped byte: header, counts, a fingerprint or the checksum itself.
        for (i in whole.indices) {
            file.writeBytes(whole.copyOf().also { it[i] = (it[i].toInt() xor 0x40).toByte() })
            assertNull("byte $i flipped", BlocklistCache.read(file))
        }
    }

    @Test
    fun a_count_beyond_the_cap_is_refused_before_allocating() {
        val header = java.nio.ByteBuffer.allocate(28)
            .putInt(0x50474243).putInt(2).putInt(Int.MAX_VALUE).putInt(0).putInt(0).putInt(0).putInt(0)
        file.writeBytes(header.array())
        assertNull(BlocklistCache.read(file))
    }
}
