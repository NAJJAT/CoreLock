package com.privacyguard.core.http

/**
 * Reads the site name from the start of a cleartext HTTP/1.x request (F-8), so a
 * blocked name reached over plain HTTP without our DNS gets the same decision as a TLS
 * server name. The bytes come from any app: every read is bounds-checked, the header
 * block is capped at [MAX_HEADER_BYTES], nothing throws, and no regex runs.
 *
 * The name is the request target's authority when the target is absolute
 * ("GET http://host/…", or CONNECT's "host:port"), else the Host header. A server uses
 * the target over the Host header (RFC 9112 §3.2.2), so when both are there they must
 * agree; two Host headers, or a Host that is no hostname, are [Result.Unreadable].
 */
object HttpRequestHost {

    /** Requests whose header block is longer are not read (the caller fails closed). */
    const val MAX_HEADER_BYTES = 8_192

    sealed interface Result {
        /** Not an HTTP/1.x request start: classify the stream some other way. */
        data object NotHttp : Result
        /** Looks like a request, but the header block hasn't ended yet. */
        data object NeedMore : Result
        /** A complete request with no name to check (no Host, an empty one, or an address). */
        data object NoHost : Result
        /** Over the cap, ambiguous or malformed: the name can't be trusted. */
        data object Unreadable : Result
        /** Lowercase, without port or trailing dot. */
        data class Host(val name: String) : Result
    }

    private val METHODS = arrayOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "PATCH", "CONNECT", "TRACE")

    fun read(data: ByteArray, length: Int = data.size): Result {
        val len = minOf(length, data.size)
        if (len == 0) return Result.NotHttp
        val method = matchMethod(data, len) ?: return Result.NotHttp
        if (method.isEmpty()) return Result.NeedMore   // a method's prefix, cut short

        // The header block must end inside the cap.
        val limit = minOf(len, MAX_HEADER_BYTES)
        val end = indexOfBlankLine(data, limit)
        if (end < 0) return if (len >= MAX_HEADER_BYTES) Result.Unreadable else Result.NeedMore

        // Request line: METHOD SP target SP version
        val lineEnd = indexOfLf(data, 0, end)
        val targetStart = method.length + 1
        var targetEnd = targetStart
        while (targetEnd < lineEnd && data[targetEnd] != SP) targetEnd++
        val targetName: Result? = when {
            method == "CONNECT" -> hostResult(data, targetStart, targetEnd)
            startsWithIgnoreCase(data, targetStart, targetEnd, "http://") -> {
                val aStart = targetStart + 7
                var aEnd = aStart
                while (aEnd < targetEnd && data[aEnd] != SLASH && data[aEnd] != QUESTION && data[aEnd] != HASH) aEnd++
                hostResult(data, aStart, aEnd)
            }
            else -> null
        }
        if (targetName == Result.Unreadable) return Result.Unreadable

        // Header lines.
        var hostHeader: Result? = null
        var p = lineEnd + 1
        while (p < end) {
            val e = indexOfLf(data, p, end)
            if (e - p >= 5 && startsWithIgnoreCase(data, p, e, "host:")) {
                if (hostHeader != null) return Result.Unreadable   // two Host headers
                var vs = p + 5
                var ve = e
                if (ve > vs && data[ve - 1] == CR) ve--
                while (vs < ve && (data[vs] == SP || data[vs] == TAB)) vs++
                while (ve > vs && (data[ve - 1] == SP || data[ve - 1] == TAB)) ve--
                hostHeader = hostResult(data, vs, ve)
                if (hostHeader == Result.Unreadable) return Result.Unreadable
            }
            p = e + 1
        }

        if (targetName != null) {
            if (hostHeader != null && hostHeader != Result.NoHost && hostHeader != targetName) return Result.Unreadable
            return targetName
        }
        return hostHeader ?: Result.NoHost
    }

    /** The method if [data] starts with one and a space; "" for a cut-short prefix; null otherwise. */
    private fun matchMethod(data: ByteArray, len: Int): String? {
        for (m in METHODS) {
            val n = minOf(len, m.length + 1)
            var ok = true
            for (i in 0 until n) {
                val want = if (i < m.length) m[i].code.toByte() else SP
                if (data[i] != want) { ok = false; break }
            }
            if (ok) return if (len > m.length) m else ""
        }
        return null
    }

    /**
     * A host[:port] in data[from, to): [Result.Host], [Result.NoHost] for an empty value
     * or an address, [Result.Unreadable] for anything that is not a hostname.
     */
    private fun hostResult(data: ByteArray, from: Int, to: Int): Result {
        if (from >= to) return Result.NoHost
        if (data[from] == '['.code.toByte()) {
            // An IPv6 literal: "[…]" with an optional ":port". Address rules apply, not names.
            var i = from + 1
            while (i < to && data[i] != ']'.code.toByte()) {
                val c = data[i].toInt().toChar()
                if (!(c.isAsciiHex() || c == ':' || c == '.')) return Result.Unreadable
                i++
            }
            if (i >= to) return Result.Unreadable
            return if (i + 1 == to || (data[i + 1] == COLON && isPort(data, i + 2, to))) Result.NoHost else Result.Unreadable
        }
        var nameEnd = to
        var colon = -1
        for (i in from until to) if (data[i] == COLON) { colon = i; break }
        if (colon >= 0) {
            if (!isPort(data, colon + 1, to)) return Result.Unreadable
            nameEnd = colon
        }
        if (nameEnd > from && data[nameEnd - 1] == DOT) nameEnd--
        val n = nameEnd - from
        if (n <= 0 || n > 253) return Result.Unreadable
        var allDigitsOrDots = true
        val chars = CharArray(n)
        for (i in 0 until n) {
            var c = data[from + i].toInt().toChar()
            if (c in 'A'..'Z') c = c + 32
            val ok = c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_'
            if (!ok) return Result.Unreadable
            if (c != '.' && c !in '0'..'9') allDigitsOrDots = false
            chars[i] = c
        }
        if (chars[0] == '.' ) return Result.Unreadable
        for (i in 1 until n) if (chars[i] == '.' && chars[i - 1] == '.') return Result.Unreadable
        val name = String(chars)
        if (allDigitsOrDots && isIpv4(name)) return Result.NoHost   // an address: address rules apply
        return Result.Host(name)
    }

    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.toInt() <= 255 }
    }

    private fun isPort(data: ByteArray, from: Int, to: Int): Boolean {
        if (from >= to || to - from > 5) return false
        var v = 0
        for (i in from until to) {
            val d = data[i] - '0'.code.toByte()
            if (d !in 0..9) return false
            v = v * 10 + d
        }
        return v in 1..65_535
    }

    private fun Char.isAsciiHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Index just past "\r\n\r\n" (or "\n\n") within data[0, limit), or -1. */
    private fun indexOfBlankLine(data: ByteArray, limit: Int): Int {
        var i = 0
        while (i < limit) {
            if (data[i] == LF) {
                if (i + 1 < limit && data[i + 1] == LF) return i + 2
                if (i + 2 < limit && data[i + 1] == CR && data[i + 2] == LF) return i + 3
            }
            i++
        }
        return -1
    }

    /** The next LF at or after [from], or [end] when there is none before it. */
    private fun indexOfLf(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i < end && data[i] != LF) i++
        return i
    }

    private fun startsWithIgnoreCase(data: ByteArray, from: Int, to: Int, prefix: String): Boolean {
        if (to - from < prefix.length) return false
        for (i in prefix.indices) {
            var c = data[from + i].toInt().toChar()
            if (c in 'A'..'Z') c = c + 32
            if (c != prefix[i]) return false
        }
        return true
    }

    private const val SP = ' '.code.toByte()
    private const val TAB = '\t'.code.toByte()
    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
    private const val COLON = ':'.code.toByte()
    private const val DOT = '.'.code.toByte()
    private const val SLASH = '/'.code.toByte()
    private const val QUESTION = '?'.code.toByte()
    private const val HASH = '#'.code.toByte()
}
