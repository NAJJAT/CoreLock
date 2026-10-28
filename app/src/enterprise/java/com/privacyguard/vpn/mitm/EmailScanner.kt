package com.privacyguard.vpn.mitm

/**
 * Finds local@domain.tld, as `\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b` would
 * match it, in one pass: from each '@' the local part is read leftwards (never into an
 * earlier match) and the domain rightwards (up to the next '@').
 *
 * Shared by PiiRedactor and LeakDetector. Both run on attacker-controlled text, and the
 * regex backtracks quadratically (a 64 KB body took minutes, V-04).
 */
internal object EmailScanner {

    private fun isLocalChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._%+-"
    private fun isDomainChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-'
    private fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
    private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /**
     * Calls [onMatch] with each match's start and end, left to right, until it returns
     * false. A top-level domain [rejectTld] refuses (s, start, end) is not accepted; a
     * shorter domain may still be. Returns false when [deadline] (System.nanoTime) passed
     * before the scan finished.
     */
    fun scan(
        s: String,
        deadline: Long,
        rejectTld: (String, Int, Int) -> Boolean = { _, _, _ -> false },
        onMatch: (start: Int, end: Int) -> Boolean,
    ): Boolean {
        var copied = 0
        var at = s.indexOf('@')
        while (at >= 0) {
            if (System.nanoTime() > deadline) return false
            var start = at
            while (start > copied && isLocalChar(s[start - 1])) start--
            // The match starts at a word boundary.
            while (start < at && !(start == 0 || isWordChar(s[start - 1]) != isWordChar(s[start]))) start++
            var runEnd = at + 1
            while (runEnd < s.length && isDomainChar(s[runEnd])) runEnd++
            val end = if (start < at) domainEnd(s, at + 1, runEnd, rejectTld) else -1
            if (end > 0) {
                if (!onMatch(start, end)) return true
                copied = end
            }
            at = s.indexOf('@', maxOf(runEnd, at + 1))
        }
        return true
    }

    /** End of the longest domain in [from, runEnd) ending in ".letters{2,}" at a word boundary, or -1. */
    private fun domainEnd(s: String, from: Int, runEnd: Int, rejectTld: (String, Int, Int) -> Boolean): Int {
        var e = runEnd
        while (e > from + 2) {
            if (e == s.length || !isWordChar(s[e])) {
                var l = e
                while (l > from && isLetter(s[l - 1])) l--
                if (e - l >= 2 && l - 1 > from && s[l - 1] == '.' && !rejectTld(s, l, e)) return e
            }
            e--
        }
        return -1
    }
}
