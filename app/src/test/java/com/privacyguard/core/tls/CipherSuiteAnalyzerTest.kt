package com.privacyguard.core.tls

import org.junit.Assert.*
import org.junit.Test

class CipherSuiteAnalyzerTest {

    private fun hello(vararg ciphers: Int) = ClientHello(
        tlsVersion         = 0x0303,
        cipherSuites       = ciphers.toList(),
        compressionMethods = listOf(0),
        extensions         = emptyList(),
        supportedGroups    = emptyList(),
        ecPointFormats     = emptyList(),
        sni                = null,
        alpn               = emptyList(),
        supportedVersions  = listOf(0x0304),
    )

    // ── clean handshake ───────────────────────────────────────────────────────

    @Test
    fun modern_ciphers_only_produces_SAFE_report() {
        val report = CipherSuiteAnalyzer.analyze(
            hello(0xC02B, 0xC02C, 0x1301, 0x1302)   // ECDHE-ECDSA-AES-GCM, TLS 1.3 suites
        )
        assertEquals(CipherRisk.SAFE, report.riskLevel)
        assertEquals(0, report.weakCount)
        assertEquals(0, report.criticalCount)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun empty_cipher_list_produces_SAFE_report() {
        val report = CipherSuiteAnalyzer.analyze(hello())
        assertEquals(CipherRisk.SAFE, report.riskLevel)
    }

    // ── NULL ciphers → CRITICAL ────────────────────────────────────────────────

    @Test
    fun null_cipher_TLS_NULL_WITH_NULL_NULL_is_CRITICAL() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0000))
        assertEquals(CipherRisk.CRITICAL, report.riskLevel)
        assertEquals(1, report.criticalCount)
        assertTrue(report.issues.any { it.contains("NULL") })
    }

    @Test
    fun null_cipher_suite_name_appears_in_weak_suites() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0000))
        assertTrue(report.weakSuites.any { it.contains("NULL") })
    }

    // ── EXPORT ciphers → CRITICAL (FREAK / LOGJAM) ───────────────────────────

    @Test
    fun rsa_export_cipher_is_CRITICAL_with_FREAK_issue() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0003))   // TLS_RSA_EXPORT_WITH_RC4_40_MD5
        assertEquals(CipherRisk.CRITICAL, report.riskLevel)
        assertTrue(report.issues.any { it.contains("FREAK") })
    }

    @Test
    fun dhe_export_cipher_is_CRITICAL_with_LOGJAM_issue() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0011))   // TLS_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA
        assertEquals(CipherRisk.CRITICAL, report.riskLevel)
        assertTrue(report.issues.any { it.contains("LOGJAM") })
    }

    // ── RC4 ciphers → MEDIUM ─────────────────────────────────────────────────

    @Test
    fun rc4_cipher_produces_MEDIUM_risk() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0004))   // TLS_RSA_WITH_RC4_128_MD5
        assertEquals(CipherRisk.MEDIUM, report.riskLevel)
        assertEquals(0, report.criticalCount)
        assertTrue(report.issues.any { it.contains("RC4") })
    }

    @Test
    fun rc4_sha_cipher_produces_MEDIUM_risk() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0005))   // TLS_RSA_WITH_RC4_128_SHA
        assertEquals(CipherRisk.MEDIUM, report.riskLevel)
    }

    // ── DES ciphers → MEDIUM (SWEET32) ───────────────────────────────────────

    @Test
    fun des_cbc_cipher_produces_MEDIUM_risk_with_SWEET32_issue() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0009))   // TLS_RSA_WITH_DES_CBC_SHA
        assertEquals(CipherRisk.MEDIUM, report.riskLevel)
        assertTrue(report.issues.any { it.contains("SWEET32") || it.contains("DES") })
    }

    // ── Anonymous ciphers → CRITICAL ─────────────────────────────────────────

    @Test
    fun anonymous_cipher_is_CRITICAL() {
        // 0x0046 = TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA — in ANON_CIPHERS only (not EXPORT)
        val report = CipherSuiteAnalyzer.analyze(hello(0x0046))
        assertEquals(CipherRisk.CRITICAL, report.riskLevel)
        assertTrue(report.issues.any { it.contains("anonymous") || it.contains("Anonymous") || it.contains("MITM") })
    }

    // ── multiple weak suites escalate risk ────────────────────────────────────

    @Test
    fun three_or_more_weak_suites_produce_HIGH_risk() {
        // 3 × RC4/DES ciphers (non-critical individually) → HIGH
        val report = CipherSuiteAnalyzer.analyze(hello(0x0004, 0x0005, 0x0009))
        assertEquals(CipherRisk.HIGH, report.riskLevel)
        assertEquals(3, report.weakCount)
    }

    @Test
    fun two_weak_suites_produce_MEDIUM_risk() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0004, 0x0005))
        assertEquals(CipherRisk.MEDIUM, report.riskLevel)
    }

    // ── mix of critical and safe ──────────────────────────────────────────────

    @Test
    fun single_critical_with_safe_suites_is_still_CRITICAL() {
        val report = CipherSuiteAnalyzer.analyze(
            hello(0xC02B, 0xC02C, 0x0000)   // two safe + one NULL
        )
        assertEquals(CipherRisk.CRITICAL, report.riskLevel)
    }

    // ── deduplication ─────────────────────────────────────────────────────────

    @Test
    fun duplicate_weak_suites_are_deduplicated_in_weakSuites_list() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0004, 0x0004, 0x0004))
        assertEquals(1, report.weakSuites.size)   // deduplicated
    }

    @Test
    fun duplicate_issues_are_deduplicated() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0004, 0x0005))
        // Both are RC4 — should produce exactly one RC4 issue message
        val rc4Issues = report.issues.count { it.contains("RC4") }
        assertEquals(1, rc4Issues)
    }

    // ── unknown cipher fallback name ──────────────────────────────────────────

    @Test
    fun unknown_cipher_in_null_set_shows_hex_name() {
        val report = CipherSuiteAnalyzer.analyze(hello(0x0001))   // known NULL variant
        assertTrue(report.weakSuites.isNotEmpty())
    }
}
