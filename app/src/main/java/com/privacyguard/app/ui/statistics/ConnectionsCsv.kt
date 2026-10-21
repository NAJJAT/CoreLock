package com.privacyguard.app.ui.statistics

import com.privacyguard.app.data.db.ConnectionEntity
import com.privacyguard.app.data.db.host
import com.privacyguard.core.utils.CsvCell

/**
 * The connections CSV export, one line per stored connection. New columns go at the
 * end so files read by column position keep working.
 */
internal object ConnectionsCsv {

    const val HEADER =
        "timestamp,appName,packageName,domain,destinationIp,destinationPort,protocol,bytesSent,bytesReceived," +
            "encryptionStatus,tlsVersion,wasBlocked,wasBackground,blockSource\n"

    fun row(c: ConnectionEntity): String =
        "${c.timestamp},${cell(c.appName)},${cell(c.packageName)},${cell(c.host ?: "")},${cell(c.destinationIp)}," +
            "${c.destinationPort},${cell(c.protocol)},${c.bytesSent},${c.bytesReceived},${cell(c.encryptionStatus)}," +
            "${cell(c.tlsVersion ?: "")},${c.wasBlocked},${c.wasBackground},${cell(c.blockSource ?: "")}\n"

    private fun cell(value: String) = CsvCell.escape(value)
}
