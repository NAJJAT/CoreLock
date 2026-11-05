package com.privacyguard.core.packet

import com.privacyguard.fuzz.Fuzz
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan task 1.4, ADR 0015: the ech SvcParam (key 5) leaves HTTPS and SVCB answers. */
class EchStripperTest {

    // ── Building messages ────────────────────────────────────────────────────

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private val ECH = 5
    private val ALPN = 1
    private val IPV4HINT = 4
    private val MANDATORY = 0
    private val echValue = ByteArray(70) { (it * 7 + 3).toByte() }       // an ECHConfigList stand-in
    private val alpnValue = byteArrayOf(2, 'h'.code.toByte(), '3'.code.toByte(), 2, 'h'.code.toByte(), '2'.code.toByte())
    private val v4Value = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)

    private fun param(key: Int, value: ByteArray) = u16(key) + u16(value.size) + value

    /** SvcPriority, TargetName, params in the order given (not sorted: tests need disorder). */
    private fun svc(priority: Int, target: String, vararg params: Pair<Int, ByteArray>): ByteArray =
        u16(priority) + (if (target.isEmpty()) byteArrayOf(0) else DnsPacket.encodeName(target)) +
            params.fold(ByteArray(0)) { acc, (k, v) -> acc + param(k, v) }

    private fun rr(name: ByteArray, type: Int, rdata: ByteArray, rdLen: Int = rdata.size) =
        name + u16(type) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(rdLen) + rdata

    private val atQuestion = byteArrayOf(0xC0.toByte(), 12)

    private fun message(qtype: Int, answers: List<ByteArray>, authority: List<ByteArray> = emptyList(), additional: List<ByteArray> = emptyList()): ByteArray {
        val header = u16(0x1234) + u16(0x8180) + u16(1) + u16(answers.size) + u16(authority.size) + u16(additional.size)
        val question = DnsPacket.encodeName("www.site.example") + u16(qtype) + u16(1)
        return (answers + authority + additional).fold(header + question) { acc, r -> acc + r }
    }

    /** The SvcParam keys of an HTTPS/SVCB rdata, in order. */
    private fun keysOf(rdata: ByteArray): List<Int> {
        var p = 2
        while (rdata[p].toInt() != 0) p += 1 + rdata[p]
        p++
        val keys = mutableListOf<Int>()
        while (p < rdata.size) {
            keys += ((rdata[p].toInt() and 0xFF) shl 8) or (rdata[p + 1].toInt() and 0xFF)
            p += 4 + (((rdata[p + 2].toInt() and 0xFF) shl 8) or (rdata[p + 3].toInt() and 0xFF))
        }
        return keys
    }

    /**
     * Whether a forgiving client could find an ech key here: any order, duplicates, junk
     * after the last whole parameter. Independent of [EchStripper]'s own parser.
     */
    private fun lenientHasEch(rdata: ByteArray): Boolean {
        if (rdata.size < 3) return false
        var p = 2
        while (p < rdata.size && rdata[p].toInt() != 0) {
            val len = rdata[p].toInt() and 0xFF
            if (len and 0xC0 != 0) return false          // a pointer: no client reads this target
            p += 1 + len
        }
        p++
        while (p + 4 <= rdata.size) {
            val key = ((rdata[p].toInt() and 0xFF) shl 8) or (rdata[p + 1].toInt() and 0xFF)
            if (key == ECH) return true
            p += 4 + (((rdata[p + 2].toInt() and 0xFF) shl 8) or (rdata[p + 3].toInt() and 0xFF))
        }
        return false
    }

    private fun stripped(msg: ByteArray): DnsPacket {
        val r = EchStripper.strip(msg)
        assertTrue("expected Stripped, got $r", r is EchStripper.Result.Stripped)
        val out = (r as EchStripper.Result.Stripped).message
        assertTrue("never larger", out.size < msg.size)
        return DnsPacket.parse(out) ?: throw AssertionError("stripped output must parse")
    }

    // ── One record, several, with and without ECH ────────────────────────────

    @Test
    fun one_record_loses_ech_and_keeps_alpn_and_hints() {
        val msg = message(65, listOf(rr(atQuestion, 65, svc(1, "", ALPN to alpnValue, IPV4HINT to v4Value, ECH to echValue))))
        val p = stripped(msg)
        val rec = p.answers.single()
        assertEquals(listOf(ALPN, IPV4HINT), keysOf(rec.rdata))
        assertEquals("www.site.example", rec.name)
        // The rest of the record is byte for byte the same.
        assertArrayEquals(svc(1, "", ALPN to alpnValue, IPV4HINT to v4Value), rec.rdata)
    }

    @Test
    fun several_records_each_lose_ech() {
        val msg = message(65, listOf(
            rr(atQuestion, 65, svc(1, "a.cdn.example", ALPN to alpnValue, ECH to echValue)),
            rr(atQuestion, 65, svc(2, "b.cdn.example", ALPN to alpnValue, IPV4HINT to v4Value, ECH to echValue.copyOf(20))),
            rr(atQuestion, 65, svc(3, "c.cdn.example", ALPN to alpnValue)),
        ))
        val p = stripped(msg)
        assertEquals(3, p.answers.size)
        assertEquals(listOf(listOf(ALPN), listOf(ALPN, IPV4HINT), listOf(ALPN)), p.answers.map { keysOf(it.rdata) })
        assertEquals(listOf("a.cdn.example", "b.cdn.example", "c.cdn.example"), p.serviceTargets)
    }

    @Test
    fun svcb_records_too() {
        val p = stripped(message(64, listOf(rr(atQuestion, 64, svc(1, "svc.example", ECH to echValue, 7 to byteArrayOf(1))))))
        assertEquals(listOf(7), keysOf(p.answers.single().rdata))
    }

    @Test
    fun records_without_ech_are_left_alone() {
        val msg = message(65, listOf(
            rr(atQuestion, 65, svc(1, "", ALPN to alpnValue, IPV4HINT to v4Value)),
            rr(atQuestion, 65, svc(0, "alias.example")),                 // AliasMode
        ))
        assertSame(EchStripper.Result.Unchanged, EchStripper.strip(msg))
    }

    @Test
    fun answers_to_other_queries_are_not_touched() {
        // An A query: even an HTTPS record in it stays (browsers read keys from HTTPS queries).
        val msg = message(1, listOf(rr(atQuestion, 1, v4Value), rr(atQuestion, 65, svc(1, "", ECH to echValue))))
        assertSame(EchStripper.Result.Unchanged, EchStripper.strip(msg))
    }

    @Test
    fun a_cname_chain_before_the_https_record_is_kept() {
        val cname = rr(atQuestion, 5, DnsPacket.encodeName("edge.cdn.example"))
        // The HTTPS record's owner points at the CNAME's target name (offset after the question).
        val cnameTargetAt = 12 + DnsPacket.encodeName("www.site.example").size + 4 + 2 + 10
        val https = rr(byteArrayOf(0xC0.toByte(), cnameTargetAt.toByte()), 65, svc(1, "", ALPN to alpnValue, ECH to echValue))
        val p = stripped(message(65, listOf(cname, https)))
        assertEquals(listOf("edge.cdn.example"), p.cnameRecords)
        assertEquals("edge.cdn.example", p.answers[1].name)
        assertEquals(listOf(ALPN), keysOf(p.answers[1].rdata))
    }

    @Test
    fun compression_pointers_after_the_removed_bytes_are_moved() {
        // Additional section: a record with a full name, then one that points at it.
        // Both sit after the removed key, so the pointer must move back by its size.
        val https = rr(atQuestion, 65, svc(1, "", ALPN to alpnValue, ECH to echValue))
        val beforeAdditional = message(65, listOf(https)).size
        val full = rr(DnsPacket.encodeName("ns.site.example"), 1, v4Value)
        val pointing = rr(byteArrayOf((0xC0 or (beforeAdditional ushr 8)).toByte(), beforeAdditional.toByte()), 28, ByteArray(16))
        val p = stripped(message(65, listOf(https), additional = listOf(full, pointing)))
        assertEquals(listOf("ns.site.example", "ns.site.example"), p.additional.map { it.name })
    }

    @Test
    fun edns_opt_record_survives() {
        val opt = byteArrayOf(0) + u16(41) + u16(1232) + byteArrayOf(0, 0, 0, 0) + u16(0)
        val p = stripped(message(65, listOf(rr(atQuestion, 65, svc(1, "", ECH to echValue))), additional = listOf(opt)))
        assertEquals(41, p.additional.single().type)
    }

    // ── Malformed parameters: the record goes, the rest stays ────────────────

    @Test
    fun a_record_with_keys_out_of_order_is_removed() {
        val bad = rr(atQuestion, 65, svc(1, "bad.example", IPV4HINT to v4Value, ALPN to alpnValue, ECH to echValue))
        val good = rr(atQuestion, 65, svc(2, "good.example", ALPN to alpnValue, ECH to echValue))
        val p = stripped(message(65, listOf(bad, good)))
        assertEquals(listOf("good.example"), p.serviceTargets)
        assertEquals(listOf(ALPN), keysOf(p.answers.single().rdata))
    }

    @Test
    fun a_record_whose_parameter_runs_past_its_end_is_removed() {
        val rdata = svc(1, "", ALPN to alpnValue) + u16(ECH) + u16(200) + echValue   // claims 200, has 70
        val bad = rr(atQuestion, 65, rdata)
        val good = rr(atQuestion, 65, svc(1, "good.example", ALPN to alpnValue))
        val p = stripped(message(65, listOf(bad, good)))
        assertEquals(listOf("good.example"), p.serviceTargets)
    }

    @Test
    fun a_record_that_makes_ech_mandatory_is_removed() {
        val bad = rr(atQuestion, 65, svc(1, "", MANDATORY to (u16(ALPN) + u16(ECH)), ALPN to alpnValue, ECH to echValue))
        val p = stripped(message(65, listOf(bad)))
        assertEquals(0, p.answers.size)
    }

    @Test
    fun a_duplicate_key_or_an_unterminated_target_removes_the_record() {
        val dup = rr(atQuestion, 65, svc(1, "", ALPN to alpnValue, ALPN to alpnValue, ECH to echValue))
        val noEnd = rr(atQuestion, 65, u16(1) + byteArrayOf(3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()))
        assertEquals(0, stripped(message(65, listOf(dup, noEnd))).answers.size)
    }

    @Test
    fun a_pointer_into_removed_bytes_makes_the_answer_unreadable() {
        val https = rr(atQuestion, 65, svc(1, "", ECH to echValue))
        val echAt = message(65, listOf(https)).size - echValue.size
        val pointing = rr(byteArrayOf((0xC0 or (echAt ushr 8)).toByte(), echAt.toByte()), 1, v4Value)
        assertSame(EchStripper.Result.Unreadable, EchStripper.strip(message(65, listOf(https), additional = listOf(pointing))))
    }

    @Test
    fun a_cut_off_answer_is_unreadable() {
        val msg = message(65, listOf(rr(atQuestion, 65, svc(1, "", ALPN to alpnValue, ECH to echValue))))
        for (len in listOf(0, 5, 11, 20, msg.size - 30, msg.size - 1)) {
            val r = EchStripper.strip(msg.copyOf(len))
            assertTrue("cut at $len: $r", r === EchStripper.Result.Unreadable)
        }
    }

    // ── Hostile input ────────────────────────────────────────────────────────

    @Test
    fun fuzz_never_throws_and_never_lets_an_ech_key_through() {
        val seeds = listOf(
            message(65, listOf(rr(atQuestion, 65, svc(1, "", ALPN to alpnValue, IPV4HINT to v4Value, ECH to echValue)))),
            message(65, listOf(rr(atQuestion, 5, DnsPacket.encodeName("edge.example")), rr(atQuestion, 65, svc(2, "x.example", ECH to echValue))),
                additional = listOf(rr(DnsPacket.encodeName("ns.example"), 1, v4Value))),
            message(64, listOf(rr(atQuestion, 64, svc(1, "", MANDATORY to u16(ALPN), ALPN to alpnValue, ECH to echValue)))),
        )
        var stripped = 0
        var unchanged = 0
        var unreadable = 0
        Fuzz.run("EchStripper", seeds, iterations = 20_000) { input ->
            val out = when (val r = EchStripper.strip(input)) {
                is EchStripper.Result.Stripped -> { stripped++; r.message }
                EchStripper.Result.Unchanged -> { unchanged++; input }
                EchStripper.Result.Unreadable -> { unreadable++; return@run }
            }
            assertTrue(out.size <= input.size)
            if (out !== input) assertNotNull("output must parse", DnsPacket.parse(out))
            // Whatever is passed on for an HTTPS/SVCB query carries no readable ech key.
            val p = DnsPacket.parse(out) ?: return@run
            val qtype = p.questions.firstOrNull()?.type
            if (qtype == 64 || qtype == 65) {
                for (rec in p.answers + p.authority + p.additional) if (rec.type == 64 || rec.type == 65) {
                    assertTrue("ech passed in ${rec.rdata.size}B record", !lenientHasEch(rec.rdata))
                }
            }
        }
        println("EchStripper fuzz: $stripped stripped, $unchanged unchanged, $unreadable unreadable")
        assertTrue("the rewrite path must be reached", stripped > 2_000 && unreadable > 500)
    }
}
