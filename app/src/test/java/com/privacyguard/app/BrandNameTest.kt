package com.privacyguard.app

import com.privacyguard.app.core.report.ItReportExporter
import com.privacyguard.vpn.mitm.SiemEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The app is called CoreLock wherever a user reads its name (owner, 2026-11-03), and some
 * "PrivacyGuard" names must stay: the inspection CA (renamed with the 90-day rotation,
 * ADR 0009), the SIEM signature header (receivers may check it), the User-Agent, and the
 * application ID (Android only updates an app with the same ID).
 */
@RunWith(RobolectricTestRunner::class)
class BrandNameTest {

    private fun source(path: String) = File(path).readText()

    /** String literals of Kotlin code lines (comments skipped): what can reach the screen. */
    private fun literals(code: String): List<String> = code.lines()
        .filterNot { it.trimStart().let { line -> line.startsWith("*") || line.startsWith("//") || line.startsWith("/*") } }
        .flatMap { line -> Regex("\"(?:[^\"\\\\]|\\\\.)*\"").findAll(line).map { it.value }.toList() }

    @Test
    fun the_app_label_and_the_report_title_say_corelock() {
        val name = RuntimeEnvironment.getApplication().getString(R.string.app_name)
        assertEquals("CoreLock", name)
        assertEquals("CoreLock Share with IT Report", ItReportExporter.pdfTitle(name))
    }

    @Test
    fun the_vpn_name_the_payload_header_and_the_pdf_use_the_app_label() {
        val vpn = source("src/main/java/com/privacyguard/platform/android/PrivacyVpnService.kt")
        assertTrue(vpn.contains(".setSession(getString(com.privacyguard.app.R.string.app_name))"))
        val payload = source("src/enterprise/java/com/privacyguard/ui/mitm/MitmScreen.kt")
        assertTrue(payload.contains("Text(stringResource(R.string.app_name).uppercase(java.util.Locale.ROOT)"))
        val report = source("src/main/java/com/privacyguard/app/core/report/ItReportExporter.kt")
        assertTrue(report.contains("pdfTitle(context.getString(com.privacyguard.app.R.string.app_name))"))
        for ((file, text) in listOf("PrivacyVpnService" to vpn, "MitmScreen" to payload, "ItReportExporter" to report)) {
            assertFalse("$file still shows the old name", literals(text).any { "PrivacyGuard" in it || "PRIVACYGUARD" in it })
        }
    }

    @Test
    fun names_that_must_not_change_stay() {
        assertEquals("com.privacyguard.app.enterprise", BuildConfig.APPLICATION_ID.removeSuffix(".debug"))
        assertEquals("X-PrivacyGuard-Sig", SiemEndpoint.SIGNATURE_HEADER)
        assertTrue(source("src/main/java/com/privacyguard/app/data/remote/BlocklistDownloader.kt")
            .contains("\"User-Agent\", \"PrivacyGuard/1.0\""))
        // The CA keeps its name, and the texts telling users which certificate to remove still match it.
        assertTrue(source("src/enterprise/java/com/privacyguard/vpn/mitm/CaManager.kt")
            .contains("CA_CN             = \"PrivacyGuard Enterprise CA\""))
        assertTrue(source("src/enterprise/res/values/strings_mitm.xml").contains("“PrivacyGuard Enterprise CA”"))
    }
}
