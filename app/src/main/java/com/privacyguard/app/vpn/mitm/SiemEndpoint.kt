package com.privacyguard.vpn.mitm

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One rule for every SIEM upload: payloads and the bearer token only ever
 * leave over HTTPS, and the body signature uses its own key, never the token.
 */
object SiemEndpoint {

    const val SIGNATURE_HEADER = "X-PrivacyGuard-Sig"

    /** The endpoint as a URI if it is an absolute https:// URL with a host, else null. */
    fun validate(endpoint: String): URI? {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null) return null   // credentials in the URL would end up in logs
        return uri
    }

    /** Hex HMAC-SHA256 of [body] under [signingKey], or null when no separate key is set. */
    fun signature(body: String, signingKey: String, bearerToken: String): String? {
        if (signingKey.isBlank() || signingKey == bearerToken) return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    /** JSON string escaping, including every control character. */
    fun escapeJson(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    append(String.format(Locale.US, "\\u%04x", c.code))
                } else {
                    append(c)
                }
            }
        }
    }
}
