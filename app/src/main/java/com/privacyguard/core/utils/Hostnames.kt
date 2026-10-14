package com.privacyguard.core.utils

/**
 * Names that arrive from untrusted sources (TLS SNI, QUIC, DNS, app labels) are
 * checked here before they are trusted as a hostname or shown on screen.
 */
object Hostnames {

    private val LABEL = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")

    /**
     * The SNI as a lower-case hostname, or null if it is not one: RFC 6066 allows
     * only an ASCII DNS name (punycode for IDNs), never an IP literal. Underscores
     * are tolerated because some CDNs use them.
     */
    fun validSni(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw.length > 254) return null
        val host = raw.trimEnd('.').lowercase()
        if (host.isEmpty() || host.length > 253) return null
        val labels = host.split('.')
        if (labels.size < 2) return null
        if (labels.all { l -> l.isNotEmpty() && l.all { it.isDigit() } }) return null   // IPv4 literal
        return host.takeIf { labels.all { LABEL.matches(it) } }
    }

    /** [validSni] on raw SNI bytes; any byte outside ASCII makes it invalid. */
    fun validSni(bytes: ByteArray, offset: Int, length: Int): String? {
        if (length <= 0 || offset < 0 || offset + length > bytes.size) return null
        for (i in offset until offset + length) if (bytes[i] < 0x21 || bytes[i] > 0x7E) return null
        return validSni(String(bytes, offset, length, Charsets.US_ASCII))
    }

    /** Bidi controls that reorder text on screen (embeddings, overrides, isolates, marks). */
    private fun isBidiControl(c: Char) =
        c in '‪'..'‮' || c in '⁦'..'⁩' || c == '‎' || c == '‏' || c == '؜'

    /**
     * A host or other machine name for display and export: printable ASCII is kept,
     * everything else (controls, bidi overrides, non-ASCII) is written as \u{XXXX},
     * so what is shown is exactly what was seen on the wire. Capped at [max] chars.
     */
    fun display(value: String, max: Int = 253): String {
        val out = StringBuilder(minOf(value.length, max))
        for (c in value) {
            if (out.length >= max) { out.append('…'); break }
            if (c in ' '..'~') out.append(c) else out.append("\\u{").append(Integer.toHexString(c.code)).append('}')
        }
        return out.toString()
    }

    /**
     * A human label (app name) for display: other scripts stay readable, but
     * control and bidi-override characters are removed and the length is capped,
     * so a label cannot reverse or hide the text around it.
     */
    fun label(value: String, max: Int = 100): String {
        val cleaned = value.filterNot { Character.isISOControl(it) || isBidiControl(it) }.trim()
        return if (cleaned.length > max) cleaned.take(max) + "…" else cleaned
    }
}
