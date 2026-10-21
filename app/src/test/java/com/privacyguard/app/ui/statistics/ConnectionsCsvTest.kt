package com.privacyguard.app.ui.statistics

import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.core.session.BlockSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionsCsvTest {

    private val header = ConnectionsCsv.HEADER.trimEnd('\n').split(',')

    private fun cells(c: ConnectionEntity): List<String> {
        val line = ConnectionsCsv.row(c)
        assertEquals('\n', line.last())
        // The values used here contain no commas, so a plain split is exact.
        return line.trimEnd('\n').split(',')
    }

    private val blocked = ConnectionEntity(
        appUid = 10123, appName = "Example", packageName = "com.example",
        destinationIp = "93.184.216.34", destinationPort = 443, sniHostname = "ads.example.com",
        protocol = "TCP", timestamp = 1_700_000_000_000L, wasBlocked = true, encryptionStatus = "TLS_1_3",
    )

    @Test
    fun block_source_is_the_last_column() {
        assertEquals("blockSource", header.last())
        // Existing columns keep their positions.
        assertEquals(listOf("timestamp", "appName", "packageName", "domain", "destinationIp", "destinationPort",
            "protocol", "bytesSent", "bytesReceived", "encryptionStatus", "tlsVersion", "wasBlocked", "wasBackground"),
            header.dropLast(1))
    }

    @Test
    fun block_source_is_written_when_recorded() {
        val row = cells(blocked.copy(blockSource = BlockSource.SNI.name))
        assertEquals(header.size, row.size)
        assertEquals("\"SNI\"", row[header.indexOf("blockSource")])
        assertEquals("true", row[header.indexOf("wasBlocked")])
        assertEquals("\"ads.example.com\"", row[header.indexOf("domain")])
    }

    @Test
    fun block_source_is_empty_when_null() {
        val row = cells(blocked)
        assertEquals(header.size, row.size)
        assertEquals("\"\"", row[header.indexOf("blockSource")])
    }
}
