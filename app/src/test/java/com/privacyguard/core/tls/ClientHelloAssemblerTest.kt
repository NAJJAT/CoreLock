package com.privacyguard.core.tls

import com.privacyguard.core.tls.ClientHelloAssembler.Complete
import com.privacyguard.core.tls.ClientHelloAssembler.NeedMore
import com.privacyguard.core.tls.ClientHelloAssembler.Unreadable
import com.privacyguard.fuzz.Fuzz
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class ClientHelloAssemblerTest {

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun message(name: String, padding: Int = 0): ByteArray {
        val n = name.toByteArray()
        val pad = if (padding > 0) u16(0x0015) + u16(padding) + ByteArray(padding) else ByteArray(0)
        val ext = pad + u16(0) + u16(n.size + 5) + u16(n.size + 3) + byteArrayOf(0) + u16(n.size) + n
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + u16(0x1301) + byteArrayOf(1, 0) + u16(ext.size) + ext
        return byteArrayOf(1, (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
    }

    private fun records(message: ByteArray, vararg cuts: Int): ByteArray {
        val bounds = listOf(0) + cuts.toList() + listOf(message.size)
        val out = ByteArrayOutputStream()
        for (i in 0 until bounds.size - 1) {
            val len = bounds[i + 1] - bounds[i]
            out.write(byteArrayOf(0x16, 3, 1, (len ushr 8).toByte(), len.toByte()))
            out.write(message, bounds[i], len)
        }
        return out.toByteArray()
    }

    @Test
    fun one_record_is_returned_as_is_without_a_copy() {
        val wire = records(message("example.com"))
        val r = ClientHelloAssembler.assemble(wire)
        assertTrue(r is Complete)
        assertSame(wire, (r as Complete).record)
        assertEquals("example.com", ClientHelloParser.parse(r.record)?.sni)
    }

    @Test
    fun every_split_into_records_gives_the_same_hello() {
        val msg = message("split.example.com", padding = 300)
        val whole = records(msg)
        for (cut in 1 until msg.size) {
            val r = ClientHelloAssembler.assemble(records(msg, cut))
            assertTrue("cut $cut", r is Complete)
            assertArrayEquals("cut $cut", whole, (r as Complete).record)
        }
        // Many one-byte records, including the 4-byte handshake header split byte by byte.
        val tiny = ClientHelloAssembler.assemble(records(msg, *IntArray(40) { it + 1 }))
        assertArrayEquals(whole, (tiny as Complete).record)
    }

    @Test
    fun every_prefix_of_a_hello_needs_more() {
        val wire = records(message("prefix.example.com"), 30)
        for (n in 0 until wire.size) assertEquals("prefix $n", NeedMore, ClientHelloAssembler.assemble(wire.copyOf(n)))
    }

    @Test
    fun bytes_after_the_hello_are_not_part_of_it() {
        val msg = message("early.example.com")
        val wire = records(msg) + byteArrayOf(0x17, 3, 3, 0, 4, 1, 2, 3, 4)   // early data record
        val r = ClientHelloAssembler.assemble(wire) as Complete
        assertArrayEquals(records(msg), r.record)
    }

    @Test
    fun what_is_not_a_readable_client_hello_is_unreadable() {
        val msg = message("bad.example.com")
        val cases = mapOf(
            "not a handshake record" to byteArrayOf(0x17, 3, 3, 0, 5, 1, 2, 3, 4, 5),
            "not TLS version 3.x" to byteArrayOf(0x16, 2, 0, 0, 5, 1, 0, 0, 1, 0),
            "empty record" to byteArrayOf(0x16, 3, 1, 0, 0) + records(msg),
            "record over 16 KB" to byteArrayOf(0x16, 3, 1, 0x40, 0x01) + ByteArray(16_385),
            "not a ClientHello" to records(msg.copyOf().also { it[0] = 2 }),
            "hello over 16 KB" to records(message("big.example.com", padding = 16_400), 10_000),
            "next record not a handshake" to records(msg.copyOf(msg.size - 5)) + byteArrayOf(0x17, 3, 3, 0, 5) + ByteArray(5),
        )
        for ((what, wire) in cases) assertEquals(what, Unreadable, ClientHelloAssembler.assemble(wire))
    }

    @Test
    fun a_buffer_at_the_cap_without_a_whole_hello_is_unreadable() {
        // A hello of exactly 16 KB fits in one record, but split in two it needs 5 bytes more
        // than the cap: at the cap the caller stops waiting, rather than buffer without end.
        val padding = 16_384 - message("cap.example.com", padding = 1).size + 1
        val msg = message("cap.example.com", padding = padding)
        assertEquals(16_384, msg.size)
        assertTrue(ClientHelloAssembler.assemble(records(msg)) is Complete)
        val split = records(msg, 8_000)
        assertEquals(NeedMore, ClientHelloAssembler.assemble(split.copyOf(ClientHelloAssembler.MAX_BYTES - 1)))
        assertEquals(Unreadable, ClientHelloAssembler.assemble(split.copyOf(ClientHelloAssembler.MAX_BYTES)))
    }

    /** Untrusted input: no exception, no hang, a result for any bytes and any split. */
    @Test
    fun fuzz_the_assembler() {
        val msg = message("fuzz.example.com", padding = 100)
        val seeds = listOf(records(msg), records(msg, 3), records(msg, 1, 2, 3, 50), records(msg, 60) + ByteArray(7))
        Fuzz.run("ClientHelloAssembler", seeds) { data ->
            when (val r = ClientHelloAssembler.assemble(data)) {
                is Complete -> {
                    assertTrue(r.record.size <= ClientHelloAssembler.MAX_BYTES)
                    ClientHelloParser.parse(r.record)
                }
                else -> Unit
            }
        }
        // Long chains of tiny records up to the cap.
        val rnd = Random(20261105)
        repeat(200) {
            val cuts = (1 until msg.size).shuffled(rnd).take(rnd.nextInt(1, 60)).sorted().toIntArray()
            assertTrue(ClientHelloAssembler.assemble(records(msg, *cuts)) is Complete)
        }
    }
}
