package com.privacyguard.vpn.mitm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for V-04 (security test 2026-10-14): hostile bodies made the email and
 * JWT regexes backtrack for minutes on the single capture worker.
 */
class PiiRedactorLinearTimeTest {

    private val size = 256 * 1024

    private val hostile = mapOf(
        "email 'a.' run" to "a.".repeat(size / 2),
        "email '@' without TLD" to "a@" + "a.".repeat(size / 2),
        "email many '@'" to "a@b".repeat(size / 3),
        "email letters only domain" to "x@" + "a".repeat(size),
        "jwt 'eyJ-' run" to "eyJ-".repeat(size / 4),
        "jwt two parts only" to ("eyJabc.def ").repeat(size / 11),
        "card separators" to ("1" + " -".repeat(40)).repeat(size / 81),
        "bearer whitespace" to ("Bearer " + " ".repeat(200)).repeat(size / 207),
        "json unterminated" to "\"password\":\"".repeat(size / 12),
    )

    @Test
    fun every_pass_is_fast_on_hostile_bodies() {
        // Without a budget, so a slow pass shows up as slow instead of being cut off.
        val r = PiiRedactor(budgetMs = 60_000)
        for ((name, body) in hostile) {
            val t0 = System.nanoTime()
            r.redact(body)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("$name (${body.length} chars) took $ms ms", ms < 1_500)
        }
    }

    @Test
    fun out_of_budget_fails_closed() {
        val r = PiiRedactor(budgetMs = 0)
        val out = r.redact("mail me at someone@example.com " + "x".repeat(100_000))
        assertFalse(out.contains("someone@example.com"))
        assertTrue(out.startsWith("[REDACTED:"))
    }

    @Test
    fun scanners_match_what_the_regexes_matched() {
        val r = PiiRedactor()
        assertEquals("to [EMAIL], cc [EMAIL].", r.redact("to first.last+tag@mail.example.co.uk, cc x_y@b-c.org."))
        assertEquals("not a@b.c or a@b", r.redact("not a@b.c or a@b"))                  // TLD needs 2+ letters
        assertEquals("u@example.com_x", r.redact("u@example.com_x"))                     // no \b after the TLD
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig-nature_x"
        assertEquals("t=[JWT];", r.redact("t=$jwt;"))
        assertEquals("xeyJa.b.c", r.redact("xeyJa.b.c"))                                 // no word boundary
        assertEquals("eyJa.b", r.redact("eyJa.b"))                                       // two parts
    }
}
