package com.privacyguard.vpn.mitm

import java.util.regex.Pattern

/**
 * PII Redactor - Removes personally identifiable information from payloads
 *
 * Scans text for patterns matching credit cards, emails, phone numbers, tokens,
 * and sensitive JSON fields, replacing them with placeholder text.
 *
 * Business Reason: Ensures compliance with privacy regulations when storing
 * or shipping payloads to SIEM systems by removing sensitive data.
 *
 * The text is attacker-controlled (any app, any server), so every pass runs in
 * linear time (V-04): emails and JWTs are found by hand-written scanners (their
 * regexes backtracked quadratically: a 64 KB body took minutes), the remaining
 * regexes use possessive quantifiers, and a whole call is bounded by a time
 * budget. Out of time, the text is replaced, never passed on unredacted.
 *
 * Thread Safety: Stateless, thread-safe by design.
 */
class PiiRedactor(private val budgetMs: Long = DEFAULT_BUDGET_MS) {

    companion object {
        const val DEFAULT_BUDGET_MS = 500L

        // Credit card (13-19 digits, with or without spaces/dashes)
        private val CREDIT_CARD_PATTERN = Pattern.compile(
            "\\b(?:\\d[ -]*?){13,19}\\b"
        )

        // Phone numbers (international format)
        private val PHONE_PATTERN = Pattern.compile(
            "\\b(?:\\+?\\d{1,3}[ -]?)?\\(?\\d{3}\\)?[ -]?\\d{3}[ -]?\\d{4}\\b"
        )

        // Bearer tokens
        private val BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?:Bearer\\s++)([A-Za-z0-9._-]++)"
        )

        // Sensitive JSON field names
        private val SENSITIVE_FIELDS = setOf(
            "password", "passwd", "secret", "token", "api_key", "apikey",
            "access_token", "refresh_token", "private_key", "credit_card",
            "ssn", "social_security", "bank_account"
        )

        private val JSON_FIELD_PATTERNS = SENSITIVE_FIELDS.associateWith { field ->
            Pattern.compile("\"$field\"\\s*+:\\s*+(\"[^\"]*+\"|\\d++|true|false|null)", Pattern.CASE_INSENSITIVE)
        }

        private fun isLocalChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._%+-"
        private fun isDomainChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-'
        private fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
        private fun isLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
        private fun isTokenChar(c: Char) = isWordChar(c) || c == '-'
    }

    /** Thrown inside a pass when the budget for this call is used up. */
    private class OutOfTime : RuntimeException(null, null, false, false)

    /** Gives regexes a text that stops them once [deadline] has passed. */
    private class Deadline(private val text: CharSequence, private val deadline: Long) : CharSequence {
        private var calls = 0
        override val length get() = text.length
        override fun get(index: Int): Char {
            if (++calls and 0x3FF == 0 && System.nanoTime() > deadline) throw OutOfTime()
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int) = Deadline(text.subSequence(startIndex, endIndex), deadline)
        override fun toString() = text.toString()
    }

    /**
     * Redact PII from a string
     *
     * @param input The input string (may be JSON, plain text, etc.)
     * @return String with PII replaced with placeholders
     */
    fun redact(input: String): String {
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        return try {
            var result = input
            result = CREDIT_CARD_PATTERN.matcher(Deadline(result, deadline)).replaceAll("[CARD]")
            result = redactEmails(result, deadline)
            result = PHONE_PATTERN.matcher(Deadline(result, deadline)).replaceAll("[PHONE]")
            result = redactJwts(result, deadline)
            result = BEARER_TOKEN_PATTERN.matcher(Deadline(result, deadline)).replaceAll("Bearer [TOKEN]")
            for ((field, pattern) in JSON_FIELD_PATTERNS) {
                result = pattern.matcher(Deadline(result, deadline)).replaceAll("\"$field\": \"[REDACTED]\"")
            }
            result
        } catch (_: OutOfTime) {
            // Fail closed: what could not be checked is not passed on.
            "[REDACTED: ${input.length} characters not scanned in time]"
        }
    }

    /**
     * Redact PII from headers map
     *
     * @param headers Map of header name to value
     * @return Map with redacted values
     */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> {
        val redacted = headers.toMutableMap()

        val sensitiveHeaders = setOf(
            "authorization", "cookie", "set-cookie", "x-api-key",
            "api-key", "x-auth-token", "x-csrf-token"
        )

        for ((key, value) in headers) {
            if (sensitiveHeaders.contains(key.lowercase())) {
                redacted[key] = "[REDACTED]"
            } else if (value.isNotEmpty()) {
                redacted[key] = redact(value)
            }
        }

        return redacted
    }

    /**
     * local@domain.tld, as `\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b` would
     * match it, in one pass: from each '@' the local part is read leftwards (never
     * into an earlier match) and the domain rightwards (up to the next '@').
     */
    private fun redactEmails(s: String, deadline: Long): String {
        if ('@' !in s) return s
        val out = StringBuilder(s.length)
        var copied = 0
        var at = s.indexOf('@')
        while (at >= 0) {
            if (System.nanoTime() > deadline) throw OutOfTime()
            var start = at
            while (start > copied && isLocalChar(s[start - 1])) start--
            // The match starts at a word boundary.
            while (start < at && !(start == 0 || isWordChar(s[start - 1]) != isWordChar(s[start]))) start++
            var runEnd = at + 1
            while (runEnd < s.length && isDomainChar(s[runEnd])) runEnd++
            val end = if (start < at) emailDomainEnd(s, at + 1, runEnd) else -1
            if (end > 0) {
                out.append(s, copied, start).append("[EMAIL]")
                copied = end
            }
            at = s.indexOf('@', maxOf(runEnd, at + 1))
        }
        return out.append(s, copied, s.length).toString()
    }

    /** End of the longest domain in [from, runEnd) ending in ".letters{2,}" at a word boundary, or -1. */
    private fun emailDomainEnd(s: String, from: Int, runEnd: Int): Int {
        var e = runEnd
        while (e > from + 2) {
            if (e == s.length || !isWordChar(s[e])) {
                var l = e
                while (l > from && isLetter(s[l - 1])) l--
                if (e - l >= 2 && l - 1 > from && s[l - 1] == '.') return e
            }
            e--
        }
        return -1
    }

    /** eyJ….….… as `\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b`, in one pass. */
    private fun redactJwts(s: String, deadline: Long): String {
        var i = s.indexOf("eyJ")
        if (i < 0) return s
        val out = StringBuilder(s.length)
        var copied = 0
        while (i >= 0) {
            if (System.nanoTime() > deadline) throw OutOfTime()
            var p = i + 3
            val first = p
            while (p < s.length && isTokenChar(s[p])) p++
            val boundary = i == 0 || !isWordChar(s[i - 1])
            var end = -1
            if (boundary && p > first && p < s.length && s[p] == '.') {
                var q = p + 1
                val second = q
                while (q < s.length && isTokenChar(s[q])) q++
                if (q > second && q < s.length && s[q] == '.') {
                    var r = q + 1
                    val third = r
                    while (r < s.length && isTokenChar(s[r])) r++
                    // \b after the last character: back off trailing '-' (not a word char).
                    while (r > third && !isWordChar(s[r - 1])) r--
                    if (r > third) end = r
                }
            }
            if (end > 0) {
                out.append(s, copied, i).append("[JWT]")
                copied = end
                i = s.indexOf("eyJ", end)
            } else {
                // Any "eyJ" inside this run fails at the same place: skip past it.
                i = s.indexOf("eyJ", maxOf(p, i + 1))
            }
        }
        return out.append(s, copied, s.length).toString()
    }
}
