package com.privacyguard.app.core.pcap

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PcapWriterTest {

    private val file = File.createTempFile("capture", ".pcap")

    @After
    fun tearDown() {
        PcapWriter.stopCapture()
        PcapWriter.maxBytes = PcapWriter.MAX_BYTES
        file.delete()
    }

    @Test
    fun capture_stops_at_the_size_limit_and_keeps_a_readable_file() {
        PcapWriter.maxBytes = 24 + 3 * (16 + 100)   // header + three 100-byte packets
        PcapWriter.startCaptureTo(file)
        repeat(10) { PcapWriter.write(ByteArray(100)) }

        assertFalse(PcapWriter.isCapturing())
        assertTrue(PcapWriter.stoppedAtLimit)
        assertEquals(24L + 3 * 116, file.length())
    }

    @Test
    fun under_the_limit_every_packet_is_kept() {
        PcapWriter.startCaptureTo(file)
        repeat(5) { PcapWriter.write(ByteArray(60)) }
        PcapWriter.stopCapture()

        assertFalse(PcapWriter.stoppedAtLimit)
        assertEquals(24L + 5 * 76, file.length())
    }

    @Test
    fun writer_and_reader_both_feed_the_capture() {
        // TunReader (outgoing) and TunWriter (incoming) both call write(); check TunWriter does.
        val src = File("src/main/java/com/privacyguard/vpn/tunnel/TunWriter.kt").readText()
        assertTrue(src.contains("PcapWriter.write(packet)"))
        val reader = File("src/main/java/com/privacyguard/vpn/tunnel/TunReader.kt").readText()
        assertTrue(reader.contains("PcapWriter.write(raw)"))
    }
}
