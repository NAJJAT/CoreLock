package com.privacyguard.core.tls

import java.security.MessageDigest

/**
 * JA4 TLS client fingerprint (FoxIO spec, https://github.com/FoxIO-LLC/ja4).
 *
 * Unlike JA3, JA4 sorts ciphers and extensions before hashing, so Chrome-style
 * extension-order randomisation does not produce a new fingerprint on every
 * connection. That stability is what makes a per-app baseline usable.
 *
 * Format: `t13d1516h2_8daaf6152771_e5627efa2ab1`
 *  - a: transport, TLS version, SNI present (d) or not (i), cipher count,
 *       extension count, first+last char of the first ALPN value
 *  - b: first 12 hex of sha256 over the sorted cipher list
 *  - c: first 12 hex of sha256 over sorted extensions (minus SNI and ALPN),
 *       then `_` and the signature algorithms in wire order
 *
 * Computed from the passive ClientHello only: nothing is decrypted.
 */
object Ja4Fingerprinter {

    private const val EXT_SNI = 0x0000
    private const val EXT_ALPN = 0x0010
    private const val EMPTY_HASH = "000000000000"

    private fun isGrease(v: Int) = ClientHello.isGrease(v)

    fun ja4(hello: ClientHello): String {
        val ciphers = hello.cipherSuites.filterNot(::isGrease)
        val extensions = hello.extensions.filterNot(::isGrease)

        val a = buildString {
            append('t') // Only TCP ClientHellos reach us; QUIC would be 'q'.
            append(versionCode(hello))
            append(if (EXT_SNI in extensions) 'd' else 'i')
            append(twoDigits(ciphers.size))
            append(twoDigits(extensions.size))
            append(alpnCode(hello.alpn.firstOrNull()))
        }

        val b = if (ciphers.isEmpty()) EMPTY_HASH
            else sha256Prefix(ciphers.sorted().joinToString(",") { hex4(it) })

        val hashedExts = extensions.filter { it != EXT_SNI && it != EXT_ALPN }.sorted()
        val sigAlgs = hello.signatureAlgorithms.filterNot(::isGrease)
        val c = if (hashedExts.isEmpty()) EMPTY_HASH else {
            val extPart = hashedExts.joinToString(",") { hex4(it) }
            val input = if (sigAlgs.isEmpty()) extPart
                else extPart + "_" + sigAlgs.joinToString(",") { hex4(it) }
            sha256Prefix(input)
        }

        return "${a}_${b}_$c"
    }

    private fun versionCode(hello: ClientHello): String {
        // supported_versions overrides the legacy field (TLS 1.3 always sends 0x0303 there).
        val version = hello.supportedVersions.filterNot(::isGrease).maxOrNull()
            ?: hello.tlsVersion
        return when (version) {
            0x0304 -> "13"
            0x0303 -> "12"
            0x0302 -> "11"
            0x0301 -> "10"
            0x0300 -> "s3"
            0x0002 -> "s2"
            0xfeff -> "d1"
            0xfefd -> "d2"
            0xfefc -> "d3"
            else -> "00"
        }
    }

    private fun alpnCode(first: String?): String {
        if (first.isNullOrEmpty()) return "00"
        val head = first.first()
        val tail = first.last()
        if (head.isAsciiAlphanumeric() && tail.isAsciiAlphanumeric()) return "$head$tail"
        // Spec: fall back to the first hex digit of the first byte and the last
        // hex digit of the last byte, so binary ALPN values stay printable.
        val headHex = "%02x".format(head.code and 0xFF)
        val tailHex = "%02x".format(tail.code and 0xFF)
        return "${headHex.first()}${tailHex.last()}"
    }

    private fun Char.isAsciiAlphanumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    // Locale.ROOT: an Arabic locale would otherwise emit Arabic-Indic digits and
    // change the fingerprint with the UI language.
    private fun twoDigits(n: Int) = String.format(java.util.Locale.ROOT, "%02d", n.coerceAtMost(99))

    private fun hex4(v: Int) = "%04x".format(v)

    private fun sha256Prefix(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(12)
}
