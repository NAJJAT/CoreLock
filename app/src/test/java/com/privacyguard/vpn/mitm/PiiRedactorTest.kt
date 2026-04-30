package com.privacyguard.vpn.mitm

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PiiRedactorTest {

    private lateinit var redactor: PiiRedactor

    @Before
    fun setUp() { redactor = PiiRedactor() }

    // ── email ─────────────────────────────────────────────────────────────────

    @Test
    fun email_address_is_redacted() {
        val result = redactor.redact("Contact us at user@example.com for more info")
        assertFalse(result.contains("user@example.com"))
        assertTrue(result.contains("[EMAIL]"))
    }

    @Test
    fun multiple_emails_are_all_redacted() {
        val result = redactor.redact("From a@b.com to c@d.org — also cc@ee.net")
        assertEquals(0, Regex("""[\w.+%-]+@[\w.-]+\.[A-Za-z]{2,}""").findAll(result).count())
        assertEquals(3, result.split("[EMAIL]").size - 1)
    }

    @Test
    fun non_email_text_is_unchanged() {
        val input = "Hello world, no PII here"
        assertEquals(input, redactor.redact(input))
    }

    // ── credit card ───────────────────────────────────────────────────────────

    @Test
    fun credit_card_number_is_redacted() {
        val result = redactor.redact("Card: 4111111111111111")
        assertFalse(result.contains("4111111111111111"))
        assertTrue(result.contains("[CARD]"))
    }

    @Test
    fun credit_card_with_spaces_is_redacted() {
        val result = redactor.redact("4111 1111 1111 1111")
        assertTrue(result.contains("[CARD]"))
    }

    @Test
    fun credit_card_with_dashes_is_redacted() {
        val result = redactor.redact("4111-1111-1111-1111")
        assertTrue(result.contains("[CARD]"))
    }

    // ── JWT ───────────────────────────────────────────────────────────────────

    @Test
    fun jwt_token_is_redacted() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val result = redactor.redact("Authorization token: $jwt")
        assertFalse(result.contains(jwt))
        assertTrue(result.contains("[JWT]"))
    }

    // ── Bearer token ──────────────────────────────────────────────────────────

    @Test
    fun bearer_token_is_redacted() {
        val result = redactor.redact("Authorization: Bearer abc123def456")
        assertFalse(result.contains("abc123def456"))
        assertTrue(result.contains("Bearer [TOKEN]"))
    }

    @Test
    fun bearer_redaction_is_case_insensitive() {
        val result = redactor.redact("authorization: bearer mySecretToken")
        assertTrue(result.contains("Bearer [TOKEN]") || result.contains("bearer [TOKEN]"))
        assertFalse(result.contains("mySecretToken"))
    }

    // ── phone number ─────────────────────────────────────────────────────────

    @Test
    fun us_phone_number_is_redacted() {
        val result = redactor.redact("Call us at 555-867-5309")
        assertFalse(result.contains("555-867-5309"))
        assertTrue(result.contains("[PHONE]"))
    }

    @Test
    fun phone_with_country_code_is_redacted() {
        val result = redactor.redact("+1 555 867 5309")
        assertFalse(result.contains("555 867 5309"))
        assertTrue(result.contains("[PHONE]"))
    }

    // ── JSON field redaction ──────────────────────────────────────────────────

    @Test
    fun json_password_field_is_redacted() {
        val result = redactor.redact("""{"username":"alice","password":"supersecret"}""")
        assertFalse(result.contains("supersecret"))
        assertTrue(result.contains("\"[REDACTED]\""))
    }

    @Test
    fun json_api_key_field_is_redacted() {
        val result = redactor.redact("""{"api_key":"sk-abc123xyz"}""")
        assertFalse(result.contains("sk-abc123xyz"))
        assertTrue(result.contains("\"[REDACTED]\""))
    }

    @Test
    fun json_token_field_is_redacted() {
        val result = redactor.redact("""{"token":"abc123","user":"bob"}""")
        assertFalse(result.contains("abc123"))
    }

    @Test
    fun json_ssn_field_is_redacted() {
        val result = redactor.redact("""{"ssn":"123-45-6789"}""")
        assertFalse(result.contains("123-45-6789"))
    }

    @Test
    fun non_sensitive_json_fields_are_unchanged() {
        val input = """{"name":"Alice","age":30,"city":"Berlin"}"""
        val result = redactor.redact(input)
        assertTrue(result.contains("Alice"))
        assertTrue(result.contains("Berlin"))
    }

    // ── redactHeaders ─────────────────────────────────────────────────────────

    @Test
    fun authorization_header_is_fully_redacted() {
        val headers = mapOf("Authorization" to "Bearer secret123", "Content-Type" to "application/json")
        val result = redactor.redactHeaders(headers)
        assertEquals("[REDACTED]", result["Authorization"])
        assertEquals("application/json", result["Content-Type"])
    }

    @Test
    fun cookie_header_is_fully_redacted() {
        val headers = mapOf("Cookie" to "sessionId=abc; userId=123")
        val result = redactor.redactHeaders(headers)
        assertEquals("[REDACTED]", result["Cookie"])
    }

    @Test
    fun set_cookie_header_is_fully_redacted() {
        val headers = mapOf("set-cookie" to "token=xyz; Secure; HttpOnly")
        val result = redactor.redactHeaders(headers)
        assertEquals("[REDACTED]", result["set-cookie"])
    }

    @Test
    fun x_api_key_header_is_fully_redacted() {
        val headers = mapOf("x-api-key" to "key_abc123")
        val result = redactor.redactHeaders(headers)
        assertEquals("[REDACTED]", result["x-api-key"])
    }

    @Test
    fun non_sensitive_headers_pass_through_unchanged() {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "text/html",
            "X-Request-Id" to "abc-123",
        )
        val result = redactor.redactHeaders(headers)
        assertEquals("application/json", result["Content-Type"])
        assertEquals("text/html",        result["Accept"])
        assertEquals("abc-123",          result["X-Request-Id"])
    }

    // ── no false positives ────────────────────────────────────────────────────

    @Test
    fun version_string_with_numbers_is_not_treated_as_credit_card() {
        val input = "App version 1.0.0 build 20240101"
        val result = redactor.redact(input)
        assertFalse(result.contains("[CARD]"))
    }

    @Test
    fun empty_string_returns_empty_string() {
        assertEquals("", redactor.redact(""))
    }
}
