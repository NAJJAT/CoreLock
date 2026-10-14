package com.privacyguard.core.utils

/**
 * One CSV field, safe to open in a spreadsheet (V-05). App labels and hosts are
 * chosen by apps and servers; a cell starting with = + - @ (or tab / CR) is run as a
 * formula by Excel, LibreOffice and Google Sheets even inside quotes. Such cells get
 * a leading apostrophe (OWASP's advice), then the field is quoted as RFC 4180 asks.
 */
object CsvCell {

    private val FORMULA_START = charArrayOf('=', '+', '-', '@', '\t', '\r')

    fun escape(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in FORMULA_START) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
