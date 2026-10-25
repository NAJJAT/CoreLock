package com.privacyguard.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The licenses screen must show the real license texts and credit every list the app
 * downloads. Paths are relative to the app module, as in OutboundHostsDisclosureTest.
 */
class OpenSourceNoticesTest {

    private fun raw(name: String) = File("src/main/res/raw/$name").readText().replace("\r\n", "\n")

    private fun sha256(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Hashes of the official texts (gnu.org/licenses/gpl-3.0.txt, apache.org/licenses/LICENSE-2.0.txt). */
    @Test
    fun gpl_and_apache_texts_are_the_unmodified_originals() {
        assertEquals("3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986", sha256(raw("license_gpl_3_0.txt")))
        assertEquals("cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", sha256(raw("license_apache_2_0.txt")))
    }

    /** The app shows the license the repository is published under, not a different copy. */
    @Test
    fun shown_app_license_matches_repository_license() {
        val license = File("../LICENSE")
        assertTrue("LICENSE missing at the repository root", license.isFile)
        assertEquals(license.readText().replace("\r\n", "\n").trim(), raw("license_gpl_3_0.txt").trim())
    }

    @Test
    fun notices_name_their_copyright_holders() {
        assertTrue(raw("license_sqlcipher_bsd_3.txt").startsWith("Copyright (c) 2008-2023, ZETETIC LLC"))
        assertTrue(raw("license_bouncycastle_mit.txt").contains("The Legion of the Bouncy Castle Inc."))
    }

    /** A new list source fails here until it is credited on the licenses screen. */
    @Test
    fun every_downloaded_list_source_is_credited() {
        val credits = File("src/main/res/values/strings_licenses.xml").readText()
        val credited = mapOf("hagezi" to "lic_url_hagezi", "oisd.nl" to "lic_url_oisd", "exodus-privacy.eu.org" to "lic_url_exodus")
        val urls = listOf(
            "src/main/java/com/privacyguard/app/core/blocklist/BlocklistSource.kt",
            "src/main/java/com/privacyguard/app/core/tracker/ExodusUpdater.kt",
        ).flatMap { Regex("https://[^\"]+").findAll(File(it).readText()).map { m -> m.value }.toList() }
        assertTrue(urls.isNotEmpty())
        urls.forEach { url ->
            val credit = credited.entries.firstOrNull { url.contains(it.key) }
            assertTrue("List source without a credit: $url", credit != null)
            assertTrue("Missing ${credit!!.value}", credits.contains("name=\"${credit.value}\""))
        }
    }

    @Test
    fun reflow_joins_wrapped_lines_and_keeps_list_items() {
        val text = "Copyright (c) X\r\nAll rights reserved.\r\n\r\nConditions:\n    * first item\n      continues\n    * second\n\n\n"
        assertEquals(
            listOf("Copyright (c) X All rights reserved.", "Conditions:\n* first item continues\n* second"),
            reflow(text),
        )
    }

    @Test
    fun reflow_of_the_bundled_texts_has_no_empty_paragraphs() {
        listOf("license_gpl_3_0.txt", "license_apache_2_0.txt", "license_sqlcipher_bsd_3.txt", "license_bouncycastle_mit.txt")
            .forEach { name ->
                val paragraphs = reflow(raw(name))
                assertTrue(name, paragraphs.size > 1 && paragraphs.none(String::isBlank))
            }
    }
}
