package com.privacyguard.vpn.mitm

/**
 * The parts of an inspected HTTP message that [PiiRedactor]'s patterns can't judge, made
 * safe to store and ship: values in URL queries and HTML forms become [VALUE] (the keys stay,
 * so the structure is still readable), and bodies that aren't text are reduced to their type
 * and size.
 *
 * Attacker-controlled input (any app, any server): one pass over the text with indexOf, no
 * regex, so the time is linear in the input length.
 */
internal object PayloadRedaction {

    const val VALUE = "[REDACTED]"

    /** Longer "keys" are more likely data than a field name, and are replaced. */
    private const val MAX_KEY_CHARS = 64

    /**
     * [url] with every query value replaced and the fragment dropped (it is never sent to the
     * server, so it can only be data copied into the log). The part before the query goes
     * through [redactText], which masks addresses, numbers and tokens in the path.
     */
    fun url(url: String, redactText: (String) -> String): String {
        val q = url.indexOf('?')
        val hash = url.indexOf('#')
        val pathEnd = when {
            q < 0 && hash < 0 -> url.length
            q < 0 -> hash
            hash < 0 -> q
            else -> minOf(q, hash)
        }
        val path = redactText(url.substring(0, pathEnd))
        if (q < 0 || (hash in 0 until q)) return path
        val queryEnd = if (hash > q) hash else url.length
        return path + "?" + form(url, q + 1, queryEnd, redactText)
    }

    /**
     * An `application/x-www-form-urlencoded` body (or a query) with every value replaced.
     * A key is kept only if it looks like a field name and [redactText] leaves it as it is;
     * anything else in the key position (an address, JSON, percent-encoding) is data.
     */
    fun form(text: String, redactText: (String) -> String = { it }): String = form(text, 0, text.length, redactText)

    /** Letters, digits and the separators field names use; '@', '%', quotes and braces are not. */
    private fun looksLikeFieldName(text: String, from: Int, to: Int): Boolean {
        for (k in from until to) {
            val c = text[k]
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' || c == '[' || c == ']')) return false
        }
        return true
    }

    private fun form(text: String, start: Int, end: Int, redactText: (String) -> String): String {
        val out = StringBuilder(minOf(end - start, 4_096))
        var i = start
        var first = true
        while (i <= end) {
            var amp = text.indexOf('&', i)
            if (amp < 0 || amp > end) amp = end
            if (amp > i) {
                if (!first) out.append('&')
                first = false
                // Searched only up to the '&': a whole-text indexOf here would make
                // "&&&…" without any '=' quadratic.
                var eq = -1
                var k = i
                while (k < amp) { if (text[k] == '=') { eq = k; break }; k++ }
                when {
                    // "a=b": keep the key, hide the value.
                    eq > i && eq - i <= MAX_KEY_CHARS && looksLikeFieldName(text, i, eq) &&
                        text.substring(i, eq).let { key -> redactText(key) == key } ->
                        out.append(text, i, eq).append('=').append(VALUE)
                    // A bare item ("?john@example.com"), an empty key or an overlong one: all data.
                    else -> out.append(VALUE)
                }
            }
            i = amp + 1
        }
        return out.toString()
    }

    /** What is left of a body that isn't readable text: its type and size, never its bytes. */
    fun binarySummary(contentType: String?, bytes: Int): String {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it.length <= MAX_KEY_CHARS && it.all { c -> c.isLetterOrDigit() || c in "/+.-_" } }
        return "[${type ?: "binary"} body, $bytes bytes, not stored]"
    }

    /** Decoded size of a base64 text (padding and line breaks don't count). */
    fun base64Bytes(text: String): Int {
        var chars = 0
        for (c in text) if (c != '=' && c != '\r' && c != '\n') chars++
        return chars * 3 / 4
    }

    /** The header's value, any case of [name]. */
    fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
