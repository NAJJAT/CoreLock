package com.privacyguard.app.core.blocklist

import com.privacyguard.app.core.utils.CappedInputStream
import com.privacyguard.app.core.utils.ResponseTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for V-10: what a downloaded (possibly tampered) list may load. */
class ListEntryValidatorTest {

    @Test fun public_suffixes_are_refused() {
        for (s in listOf("com", "co.uk", "com.au", "CO.UK.", "net")) assertNull(s, ListEntryValidator.normalize(s))
        assertEquals("ads.example.co.uk", ListEntryValidator.normalize("ads.example.co.uk"))
        assertEquals("doubleclick.net", ListEntryValidator.normalize("DoubleClick.NET."))
    }

    @Test fun idns_become_punycode_and_lookalikes_are_refused() {
        assertEquals("xn--bcher-kva.example", ListEntryValidator.normalize("bücher.example"))
        assertEquals("xn--e1afmkfd.xn--p1ai", ListEntryValidator.normalize("пример.рф"))            // all Cyrillic: fine
        assertEquals("xn--wgv71a119e.jp", ListEntryValidator.normalize("日本語.jp"))
        assertNull(ListEntryValidator.normalize("gоogle.com"))                                  // Latin + Cyrillic o
        assertNull(ListEntryValidator.normalize(java.net.IDN.toASCII("gоogle.com")))           // same, as punycode
        assertNull(ListEntryValidator.normalize("pаypal.com"))                                  // Cyrillic a
    }

    @Test fun malformed_entries_are_refused() {
        for (s in listOf("", ".", "1.2.3.4", "a b.com", "-ads.example.com", "ads-.example.com", "<x>.com",
            "a\u0000.com", "x".repeat(64) + ".com", "‮moc.elgoog", "localhost")) {
            assertNull("accepted '$s'", ListEntryValidator.normalize(s))
        }
    }

    @Test fun lines_are_parsed_and_deduplicated_without_holding_the_file() {
        val lines = sequenceOf("# hosts", "0.0.0.0 ads.example.com", "0.0.0.0 ADS.example.com", "0.0.0.0 co.uk", "127.0.0.1 localhost")
        assertEquals(setOf("ads.example.com"), BlocklistManager.parseLines(BlocklistSource.STEVENBLACK, lines))
    }

    @Test fun an_implausibly_large_list_is_refused() {
        val many = generateSequence(0) { it + 1 }.map { "host$it.example.com" }.take(1_001)
        assertNull(BlocklistManager.parseLines(BlocklistSource.OISD_BASIC, many, max = 1_000))
    }

    @Test fun streaming_400k_names_stays_small() {
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val names = generateSequence(0) { it + 1 }.map { "0.0.0.0 host$it.tracker-example.com" }.take(400_000)
        val t0 = System.nanoTime()
        val set = BlocklistManager.parseLines(BlocklistSource.STEVENBLACK, names)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        val usedMb = (rt.totalMemory() - rt.freeMemory() - before) / (1024 * 1024)
        println("400k names: $ms ms, ~$usedMb MB retained")
        assertEquals(400_000, set.size)
        assertTrue("took $ms ms", ms < 30_000)
    }

    @Test fun capped_stream_fails_past_the_limit() {
        val ok = CappedInputStream(ByteArray(100).inputStream(), 100).readBytes()
        assertEquals(100, ok.size)
        val thrown = runCatching { CappedInputStream(ByteArray(101).inputStream(), 100).readBytes() }.exceptionOrNull()
        assertTrue(thrown is ResponseTooLargeException)
    }
}
