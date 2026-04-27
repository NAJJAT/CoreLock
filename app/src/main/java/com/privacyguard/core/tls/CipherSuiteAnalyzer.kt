package com.privacyguard.core.tls

enum class CipherRisk { SAFE, MEDIUM, HIGH, CRITICAL }

data class CipherSuiteReport(
    val weakCount: Int,
    val criticalCount: Int,
    val weakSuites: List<String>,
    val riskLevel: CipherRisk,
    val issues: List<String>,
)

object CipherSuiteAnalyzer {

    // NULL — no encryption at all
    private val NULL_CIPHERS = setOf(0x0000, 0x0001, 0x0002)

    // RSA_EXPORT / DHE_EXPORT → FREAK / LOGJAM
    private val EXPORT_CIPHERS = setOf(
        0x0003, 0x0006, 0x0008, 0x000B, 0x000E,
        0x0011, 0x0014, 0x0017, 0x0019, 0x001A, 0x001B,
    )

    // RC4 → BEAST / RC4 bias attacks
    private val RC4_CIPHERS = setOf(0x0004, 0x0005, 0x0024, 0x0025)

    // DES / 3DES single-key → SWEET32
    private val DES_CIPHERS = setOf(0x0009, 0x000A, 0x000D, 0x000F, 0x0012, 0x0015)

    // Anonymous (no server auth) — trivially MITM-able
    private val ANON_CIPHERS = setOf(0x0017, 0x0018, 0x0046, 0x0047, 0x006C, 0x006D)

    private val NAMES = mapOf(
        0x0000 to "TLS_NULL_WITH_NULL_NULL",
        0x0003 to "TLS_RSA_EXPORT_WITH_RC4_40_MD5",
        0x0006 to "TLS_RSA_EXPORT_WITH_RC2_CBC_40_MD5",
        0x0008 to "TLS_RSA_EXPORT_WITH_DES40_CBC_SHA",
        0x000B to "TLS_DH_DSS_EXPORT_WITH_DES40_CBC_SHA",
        0x0011 to "TLS_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA",
        0x0014 to "TLS_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
        0x0004 to "TLS_RSA_WITH_RC4_128_MD5",
        0x0005 to "TLS_RSA_WITH_RC4_128_SHA",
        0x0009 to "TLS_RSA_WITH_DES_CBC_SHA",
        0x0017 to "TLS_DH_anon_WITH_RC4_128_MD5",
        0x0046 to "TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA",
    )

    fun analyze(hello: ClientHello): CipherSuiteReport {
        val issues = mutableListOf<String>()
        val weak = mutableListOf<String>()
        var critical = 0

        hello.cipherSuites.forEach { cs ->
            when {
                NULL_CIPHERS.contains(cs) -> {
                    weak += name(cs); critical++
                    issues += "NULL cipher offered — connection would be unencrypted"
                }
                EXPORT_CIPHERS.contains(cs) -> {
                    weak += name(cs); critical++
                    if (cs in setOf(0x0003, 0x0006, 0x0008)) {
                        issues += "FREAK: RSA_EXPORT cipher allows downgrade to 40-bit RSA"
                    } else {
                        issues += "LOGJAM: DHE_EXPORT cipher allows downgrade to 512-bit DH"
                    }
                }
                RC4_CIPHERS.contains(cs) -> {
                    weak += name(cs)
                    issues += "RC4 cipher offered — vulnerable to statistical bias attacks"
                }
                DES_CIPHERS.contains(cs) -> {
                    weak += name(cs)
                    issues += "DES/3DES cipher offered — SWEET32 birthday attack (CVE-2016-2183)"
                }
                ANON_CIPHERS.contains(cs) -> {
                    weak += name(cs); critical++
                    issues += "Anonymous cipher offered — no server authentication, trivially MITM-able"
                }
            }
        }

        val risk = when {
            critical > 0 -> CipherRisk.CRITICAL
            weak.size > 2 -> CipherRisk.HIGH
            weak.isNotEmpty() -> CipherRisk.MEDIUM
            else -> CipherRisk.SAFE
        }

        return CipherSuiteReport(
            weakCount = weak.size,
            criticalCount = critical,
            weakSuites = weak.distinct(),
            riskLevel = risk,
            issues = issues.distinct(),
        )
    }

    private fun name(cs: Int) = NAMES[cs] ?: "0x%04X".format(cs)
}
