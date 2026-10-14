package com.privacyguard.app.core.blocklist

import com.privacyguard.core.utils.PublicSuffix
import java.net.IDN
import java.util.Locale

/**
 * Checks one name from a downloaded list before it is loaded (V-10). A list host
 * can be compromised; the worst a bad entry may do is block that one name:
 *
 *  - IDNs are stored as punycode (xn--…), the form DNS queries use.
 *  - A label that mixes scripts (Latin "o" and Cyrillic "о" in "gооgle") is a
 *    look-alike and is refused. Chinese/Japanese mixes are normal and allowed.
 *  - A public suffix (com, co.uk, github.io…) is refused: it would block every
 *    site under it.
 */
object ListEntryValidator {

    /** More names than any supported list has (OISD full: ~400k); beyond this the download is refused. */
    const val MAX_ENTRIES_PER_SOURCE = 1_500_000

    private val CJK = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
    private val NEUTRAL = setOf(Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED)

    /** The name to block, lower-case ASCII, or null if it must not be loaded. */
    fun normalize(raw: String): String? {
        val d = raw.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (d.isEmpty() || d.length > 253 || d.startsWith('.') || '.' !in d) return null
        if (d.all { it.isDigit() || it == '.' }) return null                       // IPv4 literal
        if (d.any { it.isWhitespace() || Character.isISOControl(it) }) return null
        val ascii = if (d.all { it.code < 0x80 }) d
            else runCatching { IDN.toASCII(d, 0).lowercase(Locale.ROOT) }.getOrNull() ?: return null
        val labels = ascii.split('.')
        if (labels.any { !isLdhLabel(it) }) return null
        if (labels.any { it.startsWith("xn--") && mixesScripts(it) }) return null
        if (PublicSuffix.isPublicSuffix(ascii)) return null
        return ascii
    }

    /** Letters, digits, hyphens (not at the ends), and underscores, which some lists use. */
    private fun isLdhLabel(l: String) = l.isNotEmpty() && l.length <= 63 && !l.startsWith('-') && !l.endsWith('-') &&
        l.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

    private fun mixesScripts(punycodeLabel: String): Boolean {
        val unicode = runCatching { IDN.toUnicode(punycodeLabel, 0) }.getOrNull() ?: return true
        val scripts = unicode.codePoints().toArray()
            .map { Character.UnicodeScript.of(it) }
            .filter { it !in NEUTRAL }
            .toSet()
        return scripts.size > 1 && !CJK.containsAll(scripts)
    }
}
