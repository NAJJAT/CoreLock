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

    /**
     * One address from a downloaded IP list, in the form the tunnel compares against:
     * dotted IPv4, or IPv6 as Ipv6Packet.parseAddress writes it (RFC 5952). Null for
     * anything else, ranges, and addresses that are never a public resolver (private,
     * loopback, link-local, multicast...): a bad list must not cut the user's own network.
     */
    fun normalizeIp(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 45) return null
        return if (':' in s) ipv6(s) else ipv4(s)
    }

    private fun ipv4(s: String): String? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val b = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            // No leading zeros: "010" reads as octal in some parsers.
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
            b[i] = p.toInt().takeIf { it <= 255 } ?: return null
        }
        val special = b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
            (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
            (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
        return if (special) null else b.joinToString(".")
    }

    private fun ipv6(s: String): String? {
        if (s.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':') }) return null
        val halves = s.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? =
            if (part.isEmpty()) emptyList()
            else part.split(':').map { g -> if (g.isEmpty() || g.length > 4) return null else g.toInt(16) }
        val head = groups(halves[0]) ?: return null
        val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val missing = 8 - head.size - tail.size
        if ((halves.size == 1 && missing != 0) || (halves.size == 2 && missing < 1)) return null
        val all = head + List(if (halves.size == 2) missing else 0) { 0 } + tail
        val bytes = ByteArray(16)
        all.forEachIndexed { i, g -> bytes[i * 2] = (g ushr 8).toByte(); bytes[i * 2 + 1] = g.toByte() }
        val first = all[0]
        val special = all.all { it == 0 } || (all.take(7).all { it == 0 } && all[7] == 1) ||
            (first and 0xFFC0) == 0xFE80 || (first and 0xFE00) == 0xFC00 || (first and 0xFF00) == 0xFF00
        return if (special) null else com.privacyguard.core.packet.Ipv6Packet.parseAddress(bytes, 0)
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
