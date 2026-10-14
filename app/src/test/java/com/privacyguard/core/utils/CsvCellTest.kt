package com.privacyguard.core.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression for V-05: a cell an app or server chose must never run as a formula. */
class CsvCellTest {

    @Test fun formula_starts_are_neutralised() {
        assertEquals("\"'=HYPERLINK(\"\"https://x\"\")\"", CsvCell.escape("=HYPERLINK(\"https://x\")"))
        for (p in listOf("+cmd", "-2+3", "@SUM(1)", "\t=1", "\r=1")) {
            assertEquals("\"'$p\"", CsvCell.escape(p))
        }
    }

    @Test fun ordinary_values_are_only_quoted() {
        assertEquals("\"WhatsApp\"", CsvCell.escape("WhatsApp"))
        assertEquals("\"a, \"\"b\"\"\"", CsvCell.escape("a, \"b\""))
        assertEquals("\"\"", CsvCell.escape(""))
        assertEquals("\"2001:db8::1\"", CsvCell.escape("2001:db8::1"))
    }
}
