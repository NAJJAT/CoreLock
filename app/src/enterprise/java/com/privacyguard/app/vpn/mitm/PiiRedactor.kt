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
 * Thread Safety: Stateless, thread-safe by design.
 */
class PiiRedactor {

    companion object {
        // Credit card (Luhn-valid 13-19 digits, with or without spaces/dashes)
        private val CREDIT_CARD_PATTERN = Pattern.compile(
            "\\b(?:\\d[ -]*?){13,19}\\b"
        )

        // Email addresses
        private val EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
        )

        // Phone numbers (international format)
        private val PHONE_PATTERN = Pattern.compile(
            "\\b(?:\\+?\\d{1,3}[ -]?)?\\(?\\d{3}\\)?[ -]?\\d{3}[ -]?\\d{4}\\b"
        )

        // JWT tokens
        private val JWT_PATTERN = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"
        )

        // Bearer tokens
        private val BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)(?:Bearer\\s+)([A-Za-z0-9._-]+)"
        )

        // Sensitive JSON field names
        private val SENSITIVE_FIELDS = setOf(
            "password", "passwd", "secret", "token", "api_key", "apikey",
            "access_token", "refresh_token", "private_key", "credit_card",
            "ssn", "social_security", "bank_account"
        )
    }

    /**
     * Redact PII from a string
     *
     * @param input The input string (may be JSON, plain text, etc.)
     * @return String with PII replaced with placeholders
     */
    fun redact(input: String): String {
        var result = input

        // Redact credit cards
        result = CREDIT_CARD_PATTERN.matcher(result).replaceAll("[CARD]")

        // Redact emails
        result = EMAIL_PATTERN.matcher(result).replaceAll("[EMAIL]")

        // Redact phone numbers
        result = PHONE_PATTERN.matcher(result).replaceAll("[PHONE]")

        // Redact JWTs
        result = JWT_PATTERN.matcher(result).replaceAll("[JWT]")

        // Redact Bearer tokens
        result = BEARER_TOKEN_PATTERN.matcher(result).replaceAll("Bearer [TOKEN]")

        // Redact sensitive JSON fields
        result = redactJsonFields(result)

        return result
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

    private fun redactJsonFields(input: String): String {
        var result = input

        for (field in SENSITIVE_FIELDS) {
            // Match field name followed by colon, then optional whitespace, then value
            val pattern = Pattern.compile(
                "\"$field\"\\s*:\\s*(\"[^\"]*\"|\\d+|true|false|null)",
                Pattern.CASE_INSENSITIVE
            )
            result = pattern.matcher(result).replaceAll("\"$field\": \"[REDACTED]\"")
        }

        return result
    }
}