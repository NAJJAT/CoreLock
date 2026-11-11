package com.privacyguard.core.packet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DNS names are read the way RFC 1035 allows and no further: labels of at most 63 bytes
 * (length bytes 64-191 are reserved forms, not lengths), names of at most 255 bytes, a
 * name ends with its zero byte, and a name inside a record (CNAME, NS, PTR) ends inside
 * that record. Anything else is malformed, never a name a resolver would not read.
 */
class DnsNameBoundsTest {

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun header(qd: Int, an: Int = 0, ar: Int = 0, response: Boolean = false) =
        u16(0x1234) + u16(if (response) 0x8180 else 0x0100) + u16(qd) + u16(an) + u16(0) + u16(ar)
    private fun label(s: String) = byteArrayOf(s.length.toByte()) + s.toByteArray()
    private fun query(nameWire: ByteArray) = header(qd = 1) + nameWire + u16(1) + u16(1)

    @Test
    fun a_normal_query_is_read() {
        assertEquals("ads.example.com", DnsPacket.parse(query(DnsPacket.encodeName("ads.example.com")))?.queryName)
    }

    @Test
    fun a_reserved_label_length_is_malformed() {
        // 0x41 = 65: neither a label (max 63) nor a pointer (0xC0 prefix).
        val wire = byteArrayOf(0x41) + ByteArray(65) { 'a'.code.toByte() } + label("com") + byteArrayOf(0)
        assertNull(DnsPacket.parse(query(wire)))
    }

    @Test
    fun a_63_byte_label_is_fine() {
        val l = "a".repeat(63)
        assertEquals("$l.com", DnsPacket.parse(query(label(l) + label("com") + byteArrayOf(0)))?.queryName)
    }

    @Test
    fun a_name_longer_than_255_bytes_is_malformed() {
        val wire = (1..5).fold(ByteArray(0)) { acc, i -> acc + label(('a' + i).toString().repeat(63)) } + byteArrayOf(0)
        assertNull(DnsPacket.parse(query(wire)))
    }

    /**
     * A question whose name continues through a chain of pointers: each record's name is a
     * label of [len] bytes, then a pointer to the next record. Short on the wire, but the
     * question's name is 5 labels long.
     */
    private fun ptr(offset: Int) = byteArrayOf((0xC0 or (offset ushr 8)).toByte(), offset.toByte())

    private fun pointerChain(len: Int): ByteArray {
        val start = 12 + (1 + len) + 2 + 4
        var chain = ByteArray(0)
        for (i in 0 until 4) {
            // name: label + pointer (2) or end (1); then type, class, TTL, data length (10), no data.
            val next = start + chain.size + (1 + len) + 2 + 10
            val end = if (i < 3) ptr(next) else byteArrayOf(0)
            chain += label(('b' + i).toString().repeat(len)) + end +
                u16(1) + u16(1) + byteArrayOf(0, 0, 0, 0) + u16(0)
        }
        return header(qd = 1, ar = 4, response = true) +
            label("q".repeat(len)) + ptr(start) + u16(1) + u16(1) + chain
    }

    @Test
    fun a_name_longer_than_255_bytes_through_pointers_is_malformed() {
        val short = DnsPacket.parse(pointerChain(20))
        assertEquals("control: a 5-label name through pointers is read", 5 * 21 - 1, short?.queryName?.length)
        assertNull(DnsPacket.parse(pointerChain(63)))
    }

    @Test
    fun a_cname_target_must_end_inside_its_record() {
        // The CNAME's data is "\x03trk" with no end; the next record's name "\x02io\x00"
        // followed. Read on, it became "trk.io", a name the record does not hold.
        val q = DnsPacket.encodeName("a.example.com") + u16(1) + u16(1)
        val cname = byteArrayOf(0xC0.toByte(), 0x0C) + u16(5) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(4) + label("trk")
        val next = label("io") + byteArrayOf(0) + u16(1) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(4) + byteArrayOf(1, 2, 3, 4)
        val parsed = DnsPacket.parse(header(qd = 1, an = 1, ar = 1, response = true) + q + cname + next)
        assertTrue("CNAME target read past its record: ${parsed?.cnameRecords}", parsed == null || parsed.cnameRecords.isEmpty())
    }

    @Test
    fun a_cname_target_that_ends_in_its_record_is_read() {
        val q = DnsPacket.encodeName("a.example.com") + u16(1) + u16(1)
        val target = DnsPacket.encodeName("trk.example.net")
        val cname = byteArrayOf(0xC0.toByte(), 0x0C) + u16(5) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(target.size) + target
        val parsed = DnsPacket.parse(header(qd = 1, an = 1, response = true) + q + cname)
        assertNotNull(parsed)
        assertEquals(listOf("trk.example.net"), parsed!!.cnameRecords)
    }
}
